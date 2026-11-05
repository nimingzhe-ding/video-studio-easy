package com.videostudio.queue;

import com.videostudio.infra.JobMetrics;
import com.videostudio.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 僵尸任务回收的边界：只有「状态是 RUNNING + 抢锁成功 + 长时间没更新」同时成立才重新排队。
 */
class StaleJobReaperTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RedissonClient redis = mock(RedissonClient.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final JobMetrics metrics = mock(JobMetrics.class);

    @Test
    void workerStillHoldingTheLockIsNeverTouched() throws InterruptedException {
        staleRow(9L, 1);
        RLock busy = lock(false);
        when(redis.getLock("worker:job:9")).thenReturn(busy);

        new StaleJobReaper(jdbc, redis, jobs, metrics, 15, 3).reclaim();

        verify(jdbc, never()).update(contains("SET status = 'PENDING'"), any(), any(), any());
        verify(metrics, never()).jobRequeued(anyString());
    }

    @Test
    void orphanedRunningJobIsRequeuedAndKeepsItsCheckpoint() throws InterruptedException {
        staleRow(9L, 1);
        RLock free = lock(true);
        when(redis.getLock("worker:job:9")).thenReturn(free);
        when(jdbc.update(contains("SET status = 'PENDING'"), any(), any(), any())).thenReturn(1);

        new StaleJobReaper(jdbc, redis, jobs, metrics, 15, 3).reclaim();

        // 重新排队后必须补一条新的发件箱记录，否则任务会回到「永远没人消费」的状态
        verify(jdbc).update(contains("INSERT INTO job_outbox"), eq(9L));
        verify(jobs).addNotice(eq(9L), contains("自动重新排队"));
        verify(metrics).jobRequeued("stale");
        // 抢到的锁必须还回去，否则真实的 worker 永远拿不到这个任务
        verify(free).unlock();
    }

    @Test
    void repeatedlyOrphanedJobStopsAutoRequeueing() throws InterruptedException {
        // 已经重跑过 3 次，再自动拉起只会重复烧钱
        staleRow(9L, 4);
        RLock free = lock(true);
        when(redis.getLock("worker:job:9")).thenReturn(free);
        when(jdbc.update(contains("SET status = 'FAILED'"), any(), any(), any())).thenReturn(1);

        new StaleJobReaper(jdbc, redis, jobs, metrics, 15, 3).reclaim();

        verify(jdbc, never()).update(contains("INSERT INTO job_outbox"), any(Object[].class));
        verify(metrics).jobRequeued("gave_up");
    }

    private void staleRow(long id, int generation) {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.<Map<String, Object>>of(Map.of("id", id, "generation", generation)));
    }

    /** acquired=true 表示抢锁成功（没有 worker 在跑），false 表示锁被别人占着 */
    private static RLock lock(boolean acquired) throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(acquired);
        when(lock.isHeldByCurrentThread()).thenReturn(acquired);
        return lock;
    }
}
