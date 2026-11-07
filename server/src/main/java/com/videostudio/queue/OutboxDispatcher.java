package com.videostudio.queue;

import com.videostudio.common.Json;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.SendStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 事务发件箱投递器：把 job_outbox 里待发的任务消息送到 RocketMQ。
 *
 * <p>为什么不用一个 {@code @Transactional} 方法把「取记录 + 发送 + 标记」一把包住：
 * RocketMQ 的 {@code syncSend} 在 Broker 卡顿时会一直阻塞到超时（5 秒），一次取 10 条最坏就是
 * 50 秒。这 50 秒里 {@code FOR UPDATE} 拿到的行锁和事务会一直悬着，既挡住其它实例，
 * 也把连接池拖住。所以这里拆成三段：
 *
 * <ol>
 *   <li><b>领取</b>（短事务）：{@code SELECT ... FOR UPDATE SKIP LOCKED} 之后立刻把
 *       {@code next_attempt_at} 推后一个租期，使这条记录在租期内对其它实例不可见，然后提交。
 *       行锁只持有毫秒级。</li>
 *   <li><b>发送</b>（无事务）：真正调用 Broker，慢也只慢在对外的网络调用上。</li>
 *   <li><b>标记</b>（自动提交）：成功写 SENT，失败写退避时间。</li>
 * </ol>
 *
 * <p>用 {@code next_attempt_at} 当租约而不是新增 SENDING 状态，是为了让「进程在领取后崩溃」
 * 这条路径自动恢复：租期一过记录重新可领，不需要额外的巡检去捞卡在中间态的数据。
 *
 * <p>整体语义仍是<b>至少一次</b>：发送成功但标记失败时消息会重投，由消费端的
 * {@code job_id + generation} 幂等校验兜住。
 */
@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    /** 单批取多少条。取多了只会拉长一轮的耗时，不会提高吞吐。 */
    private static final int BATCH = 10;
    /** 领取之后的租期：这段时间内别的实例不会再取到同一批记录。 */
    private static final int LEASE_SECONDS = 60;
    /** 发送超时，必须明显小于租期，否则发送还没结束租约就过期了。 */
    private static final long SEND_TIMEOUT_MS = 5_000;

    private final JdbcTemplate jdbc; private final RocketMQTemplate mq; private final String topic;
    private final com.videostudio.infra.JobMetrics metrics;
    private final TransactionTemplate transactions;

    public OutboxDispatcher(JdbcTemplate jdbc, RocketMQTemplate mq,
                            @Value("${rocketmq.topic.analysis}") String topic,
                            com.videostudio.infra.JobMetrics metrics,
                            TransactionTemplate transactions) {
        this.jdbc = jdbc; this.mq = mq; this.topic = topic; this.metrics = metrics;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${queue.outbox-interval-ms:2000}")
    public void dispatch() {
        List<Map<String, Object>> claimed = claim();
        for (var row : claimed) {
            long id = ((Number) row.get("id")).longValue();
            long job = ((Number) row.get("job_id")).longValue();
            int generation = ((Number) row.get("generation")).intValue();
            throwOrMark(id, job, generation, ((Number) row.get("attempts")).intValue());
        }
        refreshBacklog();
    }

    /**
     * 短事务：锁定一批到期的记录，并把 {@code next_attempt_at} 推到租期之外。
     * <p>整个方法里只有两条 SQL，没有任何外部调用，所以事务是毫秒级的。
     * <p>租期必须从 {@code NOW()} 起算而不是从旧值累加：积压记录（例如进程重启后）的
     * {@code next_attempt_at} 可能已经过去几分钟，加 60 秒仍在过去，其它实例会立刻再次领取，
     * 租约等于没生效。
     */
    private List<Map<String, Object>> claim() {
        return transactions.execute(status -> {
            var rows = jdbc.queryForList("""
                    SELECT id,job_id,generation,attempts FROM job_outbox
                    WHERE status='PENDING' AND next_attempt_at<=NOW(3)
                    ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                    """, BATCH);
            if (rows.isEmpty()) return rows;
            jdbc.update("""
                    UPDATE job_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,?,NOW(3))
                    WHERE status='PENDING' AND next_attempt_at<=NOW(3) AND id IN (%s)
                    """.formatted(rows.stream().map(r -> ((Number) r.get("id")).longValue())
                        .map(String::valueOf).collect(java.util.stream.Collectors.joining(","))),
                    LEASE_SECONDS);
            return rows;
        });
    }

    /** 无事务：对外发送。成功标记 SENT，失败写退避时间。 */
    private void throwOrMark(long id, long job, int generation, int attempts) {
        try {
            var message = MessageBuilder.withPayload(Json.write(new DispatchMessage(job, generation)))
                    .setHeader("KEYS", job + ":" + generation).build();
            var sent = mq.syncSend(topic, message, SEND_TIMEOUT_MS);
            if (sent.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Broker 未确认持久化消息");
            }
            jdbc.update("UPDATE job_outbox SET status='SENT',sent_at=NOW(3),last_error=NULL WHERE id=?", id);
        } catch (RuntimeException ex) {
            // 指数退避，封顶 300 秒。租期已经不生效了，这里必须把下次可领时间写实。
            int next = attempts + 1;
            jdbc.update("""
                    UPDATE job_outbox SET attempts=?,next_attempt_at=TIMESTAMPADD(SECOND,?,NOW(3)),last_error=?
                    WHERE id=?
                    """, next, Math.min(300, 1 << Math.min(next, 8)), "RocketMQ 投递未确认，将自动重试", id);
            log.warn("RocketMQ dispatch failed outbox={}", id, ex);
        }
    }

    /**
     * 把积压量、最老记录年龄和死信数量刷进指标。
     * <p>积压是「消息发不出去」时最早能看出来的信号：Broker 不可用时 PENDING 只增不减，
     * 而最老记录年龄能区分「瞬时抖动」和「链路已经断了」。
     */
    private void refreshBacklog() {
        try {
            var row = jdbc.queryForMap("""
                    SELECT COUNT(*) AS pending,
                           COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),NOW(3)),0) AS oldest
                    FROM job_outbox WHERE status='PENDING'
                    """);
            Long failed = jdbc.queryForObject("SELECT COUNT(*) FROM failed_delivery", Long.class);
            metrics.outbox(((Number) row.get("pending")).longValue(), ((Number) row.get("oldest")).longValue(),
                    failed == null ? 0 : failed);
            Long invalid=jdbc.queryForObject("SELECT COUNT(*) FROM failed_delivery WHERE failure_kind='INVALID_MESSAGE' AND resolved_at IS NULL",Long.class);
            metrics.invalidMessagesPending(invalid==null?0:invalid);
        } catch (RuntimeException ex) {
            // 指标刷新失败只影响监控，不能反过来打断投递
            log.debug("刷新发件箱指标失败", ex);
        }
    }
}
