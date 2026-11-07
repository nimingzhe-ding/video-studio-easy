package com.videostudio.service;

import com.videostudio.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 失败投递的运维面：查询失败任务与异常消息，分别重投或记录处理结果。
 *
 * <p>没有这一层时，死信只体现在一个 Prometheus 计数上——运维知道有积压，却不知道是哪些任务，
 * 只能进数据库手写 SQL。异常消息仅允许标记处理，不能变成模型分析任务。
 *
 * <p>重投走与人工重试完全相同的路径（递增 generation + 写发件箱），所以旧代次的消息
 * 即使在 DLQ 里被误重放也无法启动新一代任务。
 */
@Service
public class FailedDeliveryService {

    /** 原始消息只保留标识与摘要；无效消息不关联业务任务。 */
    public record FailedDelivery(long id, long jobId, int generation, String messageId, String reason,
                                 java.time.Instant createdAt, boolean replayed, String failureKind,
                                 String payloadSha256, boolean replayable, boolean resolved) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JobService jobs;

    public FailedDeliveryService(JdbcTemplate jdbc, TransactionTemplate transactions, JobService jobs) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.jobs = jobs;
    }

    /** @param pendingOnly true 只看尚未重投或标记处理的 */
    public List<FailedDelivery> list(boolean pendingOnly, int limit) {
        String sql = """
                SELECT id, job_id, generation, message_id, reason, created_at, replayed_at,
                       failure_kind, payload_sha256, resolved_at
                FROM failed_delivery
                %s
                ORDER BY id DESC LIMIT ?
                """.formatted(pendingOnly ? "WHERE replayed_at IS NULL AND resolved_at IS NULL" : "");
        return jdbc.query(sql, (rs, i) -> new FailedDelivery(
                rs.getLong("id"),
                rs.getLong("job_id"),
                rs.getInt("generation"),
                rs.getString("message_id"),
                com.videostudio.common.SafeErrors.redact(rs.getString("reason")),
                com.videostudio.common.Sql.instant(rs.getTimestamp("created_at")),
                rs.getTimestamp("replayed_at") != null,
                rs.getString("failure_kind"), rs.getString("payload_sha256"),
                "TASK_FAILURE".equals(rs.getString("failure_kind")) && rs.getLong("job_id")>0
                    && rs.getInt("generation")>0 && rs.getTimestamp("replayed_at")==null && rs.getTimestamp("resolved_at")==null,
                rs.getTimestamp("resolved_at") != null), Math.max(1, Math.min(200, limit)));
    }

    /**
     * 重新投递这条失败记录对应的任务。
     *
     * <p>用的是任务级重试，而不是把原消息塞回 Broker：原消息带着旧代次，重放要么被消费端忽略，
     * 要么在任务已失败但代次未变时重复执行。递增代次才是唯一正确的「再来一次」。
     */
    public long replay(long deliveryId, long operatorId) {
        return transactions.execute(status -> {
            var rows = jdbc.queryForList("""
                    SELECT job_id, generation, replayed_at, failure_kind, resolved_at FROM failed_delivery WHERE id = ? FOR UPDATE
                    """, deliveryId);
            if (rows.isEmpty()) {
                throw ApiException.notFound("失败投递记录不存在: " + deliveryId);
            }
            Map<String, Object> row = rows.get(0);
            if (row.get("replayed_at") != null) {
                throw ApiException.conflict("这条失败记录已经重投过，请勿重复操作");
            }
            long jobId = ((Number) row.get("job_id")).longValue();
            if (!"TASK_FAILURE".equals(row.get("failure_kind")) || jobId<=0
                || ((Number)row.get("generation")).intValue()<=0 || row.get("resolved_at")!=null)
                throw ApiException.conflict("异常消息或已处理记录不能重投，请修复发送方后重新提交任务");
            jobs.retryAsAdmin(jobId);
            jdbc.update("UPDATE failed_delivery SET replayed_at = NOW(3), replayed_by = ? WHERE id = ?",
                    operatorId, deliveryId);
            return jobId;
        });
    }

    /** Acknowledges investigation of an invalid message, retaining its audit record. */
    public void resolve(long deliveryId, long operatorId) {
        transactions.executeWithoutResult(status -> {
            var rows=jdbc.queryForList("SELECT failure_kind, resolved_at FROM failed_delivery WHERE id = ? FOR UPDATE",deliveryId);
            if(rows.isEmpty()) throw ApiException.notFound("失败投递记录不存在: "+deliveryId);
            var row=rows.get(0);
            if(!"INVALID_MESSAGE".equals(row.get("failure_kind")))
                throw ApiException.conflict("任务失败请使用重投处理");
            if(row.get("resolved_at")!=null) return;
            jdbc.update("UPDATE failed_delivery SET resolved_at=NOW(3),resolved_by=? WHERE id=?",operatorId,deliveryId);
        });
    }
}
