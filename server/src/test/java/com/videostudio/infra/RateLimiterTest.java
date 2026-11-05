package com.videostudio.infra;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class RateLimiterTest {
    @Test void unavailableRedisRejectsRatherThanBypassesLimits() {
        var redis=mock(RedisSupport.class);
        when(redis.add(anyString(),anyLong(),any())).thenThrow(new IllegalStateException("Redis unavailable"));
        var limiter=new RateLimiter(redis,mock(JobMetrics.class));
        assertThrows(IllegalStateException.class,()->limiter.allow("rate:upload:1",10,Duration.ofMinutes(1),"上传"));
    }
    @Test void overLimitReturns429() {
        var redis=mock(RedisSupport.class);
        when(redis.add(anyString(),eq(1L),any())).thenReturn(11L);
        var limiter=new RateLimiter(redis,mock(JobMetrics.class));
        var error=assertThrows(com.videostudio.common.ApiException.class,()->limiter.require("rate:upload:1",10,Duration.ofMinutes(1),"上传"));
        assertEquals(429,error.status());
    }
}
