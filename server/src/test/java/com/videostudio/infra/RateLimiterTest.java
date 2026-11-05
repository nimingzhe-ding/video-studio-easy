package com.videostudio.infra;

import com.videostudio.common.ApiException;
import com.videostudio.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 限流器在「没有 Redis」的单机路径下的行为；这条路径必须是可用的，否则本地开发会被 429 打乱 */
class RateLimiterTest {

    /** 不启用 Redis ⇒ RedisSupport 走「不可用」分支，限流退化为进程内固定窗口 */
    private static RateLimiter limiter() {
        return new RateLimiter(new RedisSupport(new AppProperties(), null),
                new JobMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @Test
    void allowsUpToLimitThenRejects() {
        RateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.allow("rate:submit:1", 3, Duration.ofMinutes(1), "太快"), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(limiter.allow("rate:submit:1", 3, Duration.ofMinutes(1), "太快"), "第 4 次应被拒");
    }

    @Test
    void differentKeysAreIndependent() {
        RateLimiter limiter = limiter();
        assertTrue(limiter.allow("rate:submit:1", 1, Duration.ofMinutes(1), "x"));
        assertTrue(limiter.allow("rate:submit:2", 1, Duration.ofMinutes(1), "x"), "另一个素材不该受影响");
        assertFalse(limiter.allow("rate:submit:1", 1, Duration.ofMinutes(1), "x"));
    }

    @Test
    void windowResetsAfterExpiry() throws InterruptedException {
        RateLimiter limiter = limiter();
        assertTrue(limiter.allow("rate:upload:a", 1, Duration.ofMillis(80), "x"));
        assertFalse(limiter.allow("rate:upload:a", 1, Duration.ofMillis(80), "x"));
        Thread.sleep(120);
        assertTrue(limiter.allow("rate:upload:a", 1, Duration.ofMillis(80), "x"), "窗口过期后应重新放行");
    }

    @Test
    void zeroLimitDisablesLimiting() {
        RateLimiter limiter = limiter();
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.allow("rate:chat:1", 0, Duration.ofMinutes(1), "x"));
        }
    }

    @Test
    void requireThrowsTooManyRequests() {
        RateLimiter limiter = limiter();
        limiter.require("rate:chat:9", 1, Duration.ofMinutes(1), "追问过于频繁");
        ApiException error = assertThrows(ApiException.class,
                () -> limiter.require("rate:chat:9", 1, Duration.ofMinutes(1), "追问过于频繁"));
        assertEquals(429, error.status());
        assertTrue(error.getMessage().contains("追问过于频繁"));
    }
}
