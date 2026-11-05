package com.videostudio.infra;
import com.videostudio.config.BudgetProperties;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class BudgetGuardTest {
    @Test void usageCountsTokensRatherThanRequestsAndSurvivesRetries() {
        var props=new BudgetProperties(); props.setMaxTokensPerJob(100); props.setMaxTokensPerDay(1000);
        var redis=mock(RedisSupport.class);
        when(redis.get(anyString())).thenReturn(Optional.empty());
        when(redis.add(eq("video-studio:budget:job:7"),eq(120L),any())).thenReturn(120L);
        var guard=new BudgetGuard(props,mock(JobMetrics.class),redis);
        assertTrue(guard.consume(7,120));
        verify(redis).add(eq("video-studio:budget:job:7"),eq(120L),any(Duration.class));
        verify(redis).add(contains(":daily:"),eq(120L),any(Duration.class));
        guard.begin(7);
        verify(redis,never()).add(anyString(),eq(0L),any());
    }
    @Test void dailyLimitIsCheckedForInteractiveCallsToo() {
        var props=new BudgetProperties(); props.setMaxTokensPerDay(100);
        var redis=mock(RedisSupport.class);
        when(redis.get(contains(":daily:"))).thenReturn(Optional.of(100L));
        var guard=new BudgetGuard(props,mock(JobMetrics.class),redis);
        assertThrows(com.videostudio.common.ApiException.class,guard::requireAvailable);
    }
}
