package com.videostudio.queue;

import com.videostudio.infra.JobMetrics;
import com.videostudio.repository.JobRepository;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 回收「执行者已经不在，但任务还停在 RUNNING」的僵尸任务。
 *
 * <p>为什么需要它：消费者死在半路（发布重启、OOM、机器宕机）时，RocketMQ 的消息已经
 * 被确认，数据库里的状态却永远停在 RUNNING。这类任务既不能被重试接口接走（重试只接受
 * FAILED / CANCELED），也不会再收到消息，前端只能一直转圈。
 *
 * <p>判定依据必须是「锁没人持有」而不是仅仅「更新时间太旧」：正常执行时 Redisson 的看门狗
 * 会持续续期 {@code worker:job:<id>}，锁在就说明 worker 还活着；进程消失后锁会在
 * 看门狗超时后自动释放。两个条件同时成立才回收，避免把还在跑的长任务错误拉起。
 *
 * <p>回收动作是「重新排队」而不是「标记失败」：任务自带断点，重跑会从最后一个完成的阶段
 * 继续，不会把已经花掉的转写和抽帧再算一次钱。自动回收次数有上限，防止「一跑就崩」的任务
 * 无限循环烧钱；超过上限后交给人工判断。
 */
@Component
public class StaleJobReaper {

    private static final Logger log = LoggerFactory.getLogger(StaleJobReaper.class);

    private final JdbcTemplate jdbc;
    private final RedissonClient redis;
    private final JobRepository jobs;
    private final JobMetrics metrics;
    private final TransactionTemplate transactions;
    private final long staleMinutes;
    private final int maxAutomaticRequeue;

    public StaleJobReaper(JdbcTemplate jdbc, RedissonClient redis, JobRepository jobs, JobMetrics metrics,
                          TransactionTemplate transactions,
                          @Value("${worker.stale-minutes:15}") long staleMinutes,
                          @Value("${worker.max-automatic-requeue:3}") int maxAutomaticRequeue) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.jobs = jobs;
        this.metrics = metrics;
        this.transactions = transactions;
        this.staleMinutes = staleMinutes;
        this.maxAutomaticRequeue = maxAutomaticRequeue;
    }

    @Scheduled(fixedDelayString = "${worker.stale-check-interval-ms:60000}")
    public void reclaim() {
        if (staleMinutes <= 0) {
            return; // 显式关闭：多实例部署但锁不可靠时，运维可以关掉自动回收
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, generation FROM analysis_job
                WHERE status = 'RUNNING' AND updated_at < TIMESTAMPADD(MINUTE, ?, NOW(3))
                ORDER BY updated_at LIMIT 20
                """, -staleMinutes);
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            int generation = ((Number) row.get("generation")).intValue();
            // 直接抢锁，而不是先 isLocked() 再改库：抢占成功就等于「此刻没有 worker 在跑」，
            // 中间不会留出「检查之后、改库之前 worker 刚好启动」的窗口。
            var lock = redis.getLock("worker:job:" + id);
            boolean acquired;
            try {
                acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!acquired) {
                continue; // worker 还活着，只是这一步很久没写进度
            }
            try {
                requeue(id, generation);
            } catch (RuntimeException ex) {
                log.warn("回收僵尸任务 {} 失败: {}", id, ex.getMessage());
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    private void requeue(long id, int generation) {
        if (generation > maxAutomaticRequeue) {
            // 已自动拉起太多次，再自动重跑只会重复烧钱，交回给人工
            int marked = jdbc.update("""
                    UPDATE analysis_job SET status = 'FAILED', stage = 'FAILED', error = ?, updated_at = NOW(3), finished_at = NOW(3)
                    WHERE id = ? AND status = 'RUNNING' AND generation = ?
                    """, "执行者多次失联，已停止自动重跑；请检查日志后手动重试", id, generation);
            if (marked == 1) {
                metrics.jobRequeued("gave_up");
                log.error("任务 {} 连续 {} 次失联，停止自动重跑", id, generation);
            }
            return;
        }
        // 「改状态」和「写发件箱」必须在同一个事务里：只改状态不写发件箱，任务会永远停在
        // PENDING 却没有任何消息来驱动它（OutboxDispatcher 只扫 job_outbox，没有别的补偿路径）。
        Boolean requeued = transactions.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE analysis_job SET status = 'PENDING', updated_at = NOW(3), error = NULL, finished_at = NULL,
                                           generation = generation + 1
                    WHERE id = ? AND status = 'RUNNING' AND generation = ?
                      AND updated_at < TIMESTAMPADD(MINUTE, ?, NOW(3))
                    """, id, generation, -staleMinutes);
            if (updated != 1) {
                return false; // 期间被别的实例处理了，或者任务刚好自己跑完
            }
            jdbc.update("""
                    INSERT INTO job_outbox (job_id, generation, status, next_attempt_at, created_at)
                    SELECT id, generation, 'PENDING', NOW(3), NOW(3) FROM analysis_job WHERE id = ?
                    """, id);
            jobs.addNotice(id, "执行该任务的实例已失联，已自动重新排队并从断点继续");
            return true;
        });
        if (!Boolean.TRUE.equals(requeued)) {
            return;
        }
        metrics.jobRequeued("stale");
        log.warn("任务 {} 的执行者在 {} 分钟内没有心跳，已重新排队", id, staleMinutes);
    }
}
