package com.videostudio.infra;

import com.videostudio.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 固定窗口限流（每窗口一个计数器）。
 * <p>为什么用固定窗口而不是滑动窗口：实现只有一次 {@code INCR + EXPIRE}，天然原子，
 * 跨实例共享，出问题也容易看懂。代价是窗口边界处最多可以放过两倍流量
 * （前一窗口最后 1 秒 + 后一窗口第 1 秒），对「防止误用/刷接口」这个目标完全够用；
 * 真需要严格平滑再换成令牌桶。
 * <p>没有 Redis 时退化为进程内计数——单实例部署这就是正确的行为，
 * 多实例时每个副本各自限流，总体放行量会翻倍，这是有意的降级而不是 bug。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final RedisSupport redis;
    private final JobMetrics metrics;
    /** 没有 Redis 时的本机窗口计数：key -> 计数 */
    private final Map<String, LocalWindow> local = new ConcurrentHashMap<>();

    public RateLimiter(RedisSupport redis, JobMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    private record LocalWindow(Instant startedAt, AtomicInteger count) {
    }

    /**
     * 尝试放行一次。
     *
     * @param key      限流维度，例如 {@code rate:submit:asset-1}
     * @param limit    窗口内允许的次数，<=0 表示不限流
     * @param window   窗口长度
     * @param describe 超限时给用户看的话术，例如「提交过于频繁」
     * @return 放行返回 true，超限返回 false
     */
    public boolean allow(String key, int limit, Duration window, String describe) {
        if (limit <= 0) {
            return true;
        }
        long used = increment(key, window);
        if (used > limit) {
            // 指标里按「被限的维度」打标签：看板上一眼能看出是上传还是追问在被刷
            metrics.rateLimited(scope(key));
            log.info("限流命中 key={} used={}/{} window={}s", key, used, limit, window.toSeconds());
            return false;
        }
        return true;
    }

    /** 从 rate:submit:12 这样的 key 里取出 scope（submit / upload / chat / realtime） */
    private static String scope(String key) {
        if (key.startsWith("rate:")) {
            int end = key.indexOf(':', 5);
            return end > 5 ? key.substring(5, end) : "other";
        }
        return "other";
    }

    /** 同 {@link #allow}，但超限时直接抛 429 */
    public void require(String key, int limit, Duration window, String describe) {
        if (!allow(key, limit, window, describe)) {
            throw new ApiException(429, describe + "，请 " + Math.max(1, window.toSeconds()) + " 秒后再试");
        }
    }

    private long increment(String key, Duration window) {
        if (redis.configured() && redis.available()) {
            return redis.increment(key, window).orElse(0L);
        }
        // 本机退化实现：窗口过期就重置，注意这里用 compute 保证原子
        LocalWindow current = local.compute(key, (k, old) -> {
            Instant now = Instant.now();
            if (old == null || Duration.between(old.startedAt(), now).compareTo(window) >= 0) {
                return new LocalWindow(now, new AtomicInteger(1));
            }
            old.count().incrementAndGet();
            return old;
        });
        // compute 里已经加过一次；首次创建时计数是 1
        return current.count().get();
    }

    /** 清理过期的本机窗口，由定时任务调用；量级很小，不清也不会明显泄漏 */
    public int purgeLocal(Duration window) {
        Instant cutoff = Instant.now().minus(window);
        int before = local.size();
        local.entrySet().removeIf(entry -> entry.getValue().startedAt().isBefore(cutoff));
        return before - local.size();
    }
}
