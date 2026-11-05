package com.videostudio.infra;

import com.videostudio.common.ApiException;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class RateLimiter {
    private final RedisSupport redis;
    private final JobMetrics metrics;
    public RateLimiter(RedisSupport redis,JobMetrics metrics) { this.redis=redis; this.metrics=metrics; }
    public boolean allow(String key,int limit,Duration window,String describe) {
        if(limit<=0) return true;
        long used=redis.add(key,1,window);
        if(used<=limit) return true;
        String[] parts=key.split(":");
        metrics.rateLimited(parts.length>1?parts[1]:"other");
        return false;
    }
    public void require(String key,int limit,Duration window,String describe) {
        if(!allow(key,limit,window,describe)) throw new ApiException(429,describe+"，请稍后再试");
    }
}
