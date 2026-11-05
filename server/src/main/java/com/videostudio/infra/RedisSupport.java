package com.videostudio.infra;

import com.videostudio.config.AppProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Required shared state. Redis failures propagate; limits and locks never fail open. */
@Component
public class RedisSupport {
    private final AppProperties props;
    private final StringRedisTemplate template;
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
        "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
        "local n=redis.call('incrby',KEYS[1],ARGV[1]); if n==tonumber(ARGV[1]) then redis.call('pexpire',KEYS[1],ARGV[2]) end; return n", Long.class);
    public RedisSupport(AppProperties props, StringRedisTemplate template) { this.props=props; this.template=template; }
    public boolean configured() { return true; }
    public boolean available() {
        template.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
        return true;
    }
    public void reset() {}
    public Duration lockTtl() { return Duration.ofSeconds(Math.max(5,props.getRedis().getLockTtlSeconds())); }
    public Optional<String> tryLock(String key, Duration ttl) {
        String token=UUID.randomUUID().toString();
        return Boolean.TRUE.equals(template.opsForValue().setIfAbsent(key,token,ttl)) ? Optional.of(token) : Optional.empty();
    }
    public void unlock(String key,String token) { if(token!=null) template.execute(UNLOCK,List.of(key),token); }
    public Optional<Long> increment(String key,Duration ttl) { return Optional.of(add(key,1,ttl)); }
    public long add(String key,long amount,Duration ttl) {
        Long value=template.execute(INCREMENT,List.of(key),Long.toString(amount),Long.toString(ttl.toMillis()));
        if(value==null) throw new IllegalStateException("Redis 未返回计数结果");
        return value;
    }
    public Optional<Long> get(String key) {
        String value=template.opsForValue().get(key);
        return value==null?Optional.empty():Optional.of(Long.parseLong(value));
    }
    public void publish(String channel,String payload) { template.convertAndSend(channel,payload); }
    public <T> T cached(String key,Duration ttl,Supplier<T> loader,java.util.function.Function<String,T> parser,
                       java.util.function.Function<T,String> serializer) {
        String raw=template.opsForValue().get(key);
        if(raw!=null) return parser.apply(raw);
        T value=loader.get();
        if(value!=null) template.opsForValue().set(key,serializer.apply(value),ttl);
        return value;
    }
}
