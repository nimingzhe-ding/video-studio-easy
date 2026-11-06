package com.videostudio.infra;

import com.videostudio.config.BudgetProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成本预算：单任务、全站日、单用户日三个维度。
 * <p>用户维度的意义是隔离——一个用户超额只应该挡住他自己，不能把全站额度吃光后
 * 让所有人都提交不了任务。
 */
class BudgetGuardTest {

    private final RedisSupport redis = mock(RedisSupport.class);
    private final JobOwners owners = mock(JobOwners.class);
    private final JobMetrics metrics = mock(JobMetrics.class);

    private BudgetGuard guard(BudgetProperties props) {
        return new BudgetGuard(props, metrics, redis, owners);
    }

    private BudgetProperties props(long perJob, long perDay, long perUserPerDay) {
        var props = new BudgetProperties();
        props.setMaxTokensPerJob(perJob);
        props.setMaxTokensPerDay(perDay);
        props.setMaxTokensPerUserPerDay(perUserPerDay);
        return props;
    }

    @Test
    void usageCountsTokensRatherThanRequestsAndSurvivesRetries() {
        when(redis.get(anyString())).thenReturn(Optional.empty());
        when(owners.ownerOf(7)).thenReturn(3L);
        when(redis.add(eq("video-studio:budget:job:7"), eq(120L), any())).thenReturn(120L);
        var guard = guard(props(100, 1000, 0));

        assertTrue(guard.consume(7, 120));

        verify(redis).add(eq("video-studio:budget:job:7"), eq(120L), any(Duration.class));
        verify(redis).add(contains(":daily:"), eq(120L), any(Duration.class));
        guard.begin(7);
        verify(redis, never()).add(anyString(), eq(0L), any());
    }

    @Test
    void dailyLimitIsCheckedForInteractiveCallsToo() {
        when(redis.get(contains(":daily:"))).thenReturn(Optional.of(100L));
        when(owners.ownerOf(7)).thenReturn(3L);
        var guard = guard(props(2_000_000, 100, 0));

        assertThrows(com.videostudio.common.ApiException.class, () -> guard.requireAvailable(7));
    }

    /** 用户额度用尽时只挡住他自己，全站额度还有余量 */
    @Test
    void userDailyLimitBlocksOnlyThatUser() {
        when(redis.get(anyString())).thenReturn(Optional.empty());
        when(owners.ownerOf(7)).thenReturn(3L);
        when(owners.ownerOf(8)).thenReturn(4L);
        when(redis.get("video-studio:budget:daily:user:3:" + today())).thenReturn(Optional.of(500L));
        when(redis.get("video-studio:budget:daily:user:4:" + today())).thenReturn(Optional.empty());
        var guard = guard(props(2_000_000, 10_000, 500));

        com.videostudio.common.ApiException blocked = assertThrows(
                com.videostudio.common.ApiException.class, () -> guard.requireAvailable(7));
        assertTrue(blocked.getMessage().contains("你"), "用户超额要说明是他自己的额度");
        assertEquals(null, guard.rejectReason(4L), "别的用户不受影响");
    }

    /** 全站额度用尽时所有人一起停，提示语要说清是全局原因 */
    @Test
    void globalDailyLimitStillBlocksEveryone() {
        when(owners.ownerOf(7)).thenReturn(3L);
        when(redis.get("video-studio:budget:daily:" + today())).thenReturn(Optional.of(10_000L));
        var guard = guard(props(2_000_000, 10_000, 500));

        assertTrue(guard.rejectReason(3L).contains("今日模型配额"),
                "全站额度用尽时不该把责任推给单个用户");
    }

    /** 消费时同时累加全站与用户两个键，缺一个都会让隔离失效 */
    @Test
    void consumeAccumulatesBothGlobalAndPerUserCounters() {
        when(owners.ownerOf(7)).thenReturn(3L);
        when(redis.add(anyString(), any(Long.class), any(Duration.class))).thenReturn(1L);
        var guard = guard(props(2_000_000, 10_000, 500));

        guard.consume(7, 42);

        verify(redis).add(contains(":daily:" + today()), eq(42L), any(Duration.class));
        verify(redis).add(eq("video-studio:budget:daily:user:3:" + today()), eq(42L), any(Duration.class));
    }

    /** 查不到归属（未认领的旧数据）时只按全站额度判断，不能因此把任务判死 */
    @Test
    void unknownOwnerFallsBackToGlobalLimitOnly() {
        when(owners.ownerOf(9)).thenReturn(0L);
        when(redis.get(anyString())).thenReturn(Optional.empty());
        var guard = guard(props(2_000_000, 10_000, 1));

        assertEquals(null, guard.rejectReason(0L));
        guard.requireAvailable(9);
    }

    /** 关闭用户维度（配 0）时不应产生任何用户级读写 */
    @Test
    void zeroUserLimitDisablesPerUserAccounting() {
        when(owners.ownerOf(7)).thenReturn(3L);
        when(redis.add(anyString(), any(Long.class), any(Duration.class))).thenReturn(1L);
        var guard = guard(props(2_000_000, 10_000, 0));

        guard.consume(7, 42);

        verify(redis, never()).add(contains(":daily:user:"), any(Long.class), any(Duration.class));
    }

    private static String today() {
        return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
    }
}
