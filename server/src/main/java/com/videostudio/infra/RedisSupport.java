package com.videostudio.infra;

import com.videostudio.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis 的唯一入口：所有调用都先问 {@link #available()}，不可用时返回空结果而不是抛异常。
 * <p>这样「有没有 Redis」变成部署期决定：单机模式（默认）零依赖跑通全流程，
 * 容器模式下才真正启用跨实例互斥、事件广播与全局配额。
 */
@Component
public class RedisSupport {

    private static final Logger log = LoggerFactory.getLogger(RedisSupport.class);

    /** 只删除自己持有的锁：先比对 value 再删，避免锁过期后误删别人的锁 */
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    private final AppProperties props;
    private final StringRedisTemplate template;
    /** 探测一次就记住结果：Redis 挂了之后每次调用都去连一次会拖慢请求 */
    private volatile boolean probed;
    private volatile boolean available;

    public RedisSupport(AppProperties props, StringRedisTemplate template) {
        this.props = props;
        this.template = template;
    }

    public boolean configured() {
        return props.getRedis().isEnabled();
    }

    public Duration lockTtl() {
        return Duration.ofSeconds(Math.max(5, props.getRedis().getLockTtlSeconds()));
    }

    /** 配置开启且首次探测通过才算可用；探测失败后不再重试，直到 {@link #reset()} */
    public boolean available() {
        if (!configured()) {
            return false;
        }
        if (!probed) {
            synchronized (this) {
                if (!probed) {
                    available = probe();
                    probed = true;
                }
            }
        }
        return available;
    }

    private boolean probe() {
        try {
            template.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> {
                connection.ping();
                return "PONG";
            });
            log.info("Redis 已连接，跨实例互斥与事件广播已启用");
            return true;
        } catch (RuntimeException e) {
            log.warn("Redis 配置已开启但连不上，自动退化为单机模式：{}", e.getMessage());
            return false;
        }
    }

    /** 探测失败后允许再次尝试（定时任务或管理接口调用） */
    public void reset() {
        probed = false;
    }

    /**
     * 尝试获取分布式锁。
     *
     * @return 拿到锁时返回令牌（用于 {@link #unlock}），拿不到返回空
     */
    public Optional<String> tryLock(String key, Duration ttl) {
        if (!available()) {
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = template.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(ok) ? Optional.of(token) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("获取锁 {} 失败，按单机模式继续：{}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void unlock(String key, String token) {
        if (!available() || token == null) {
            return;
        }
        try {
            template.execute(UNLOCK, List.of(key), token);
        } catch (RuntimeException e) {
            log.debug("释放锁 {} 失败: {}", key, e.getMessage());
        }
    }

    /** 自增计数器并设置过期时间，用于配额统计；Redis 不可用时返回空 */
    public Optional<Long> increment(String key, Duration ttl) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            Long value = template.opsForValue().increment(key);
            if (value != null && value == 1L) {
                template.expire(key, ttl);
            }
            return Optional.ofNullable(value);
        } catch (RuntimeException e) {
            log.warn("配额计数 {} 失败：{}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Long> get(String key) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            String raw = template.opsForValue().get(key);
            return raw == null ? Optional.empty() : Optional.of(Long.parseLong(raw));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public void publish(String channel, String payload) {
        if (!available()) {
            return;
        }
        try {
            template.convertAndSend(channel, payload);
        } catch (RuntimeException e) {
            log.debug("发布事件到 {} 失败: {}", channel, e.getMessage());
        }
    }

    /**
     * 读缓存，未命中时用 loader 计算并回填。
     * <p>刻意不缓存异常：loader 抛错直接透传，避免把失败结果写进缓存。
     */
    public <T> T cached(String key, Duration ttl, Supplier<T> loader, java.util.function.Function<String, T> parser,
                        java.util.function.Function<T, String> serializer) {
        if (available()) {
            try {
                String raw = template.opsForValue().get(key);
                if (raw != null) {
                    T hit = parser.apply(raw);
                    if (hit != null) {
                        return hit;
                    }
                }
            } catch (RuntimeException e) {
                log.debug("读缓存 {} 失败: {}", key, e.getMessage());
            }
        }
        T value = loader.get();
        if (value != null && available()) {
            try {
                template.opsForValue().set(key, serializer.apply(value), ttl);
            } catch (RuntimeException e) {
                log.debug("写缓存 {} 失败: {}", key, e.getMessage());
            }
        }
        return value;
    }
}
