package com.videostudio.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostudio.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务事件总线：把「新事件写好了」这件事广播给正在等它的 SSE 连接。
 * <p>SSE 断线重连要靠数据库回放补齐历史，所以事件本身仍然以 {@code job_event} 表为准；
 * 这里只广播「有新事件了」这一个信号，因此丢一条消息最多退化成一次轮询，不会丢数据。
 * <p>没有 Redis 时它就是纯进程内的唤醒器，单机行为不变。
 */
@Component
public class JobEventBus {

    private static final Logger log = LoggerFactory.getLogger(JobEventBus.class);
    public static final String CHANNEL = "video-studio:job-events";

    private final RedisSupport redis;
    /** jobId -> 本实例上的等待者；跨实例的消息也会被投递到这里 */
    private final Map<Long, CopyOnWriteArrayList<Runnable>> waiters = new ConcurrentHashMap<>();

    public JobEventBus(RedisSupport redis, ObjectProvider<RedisMessageListenerContainer> containers) {
        this.redis = redis;
        // 监听容器是「启用 Redis 才存在」的 bean，所以这里必须用 ObjectProvider 探测，
        // 直接注入会在单机模式下启动失败
        RedisMessageListenerContainer container = containers.getIfAvailable();
        if (container != null && redis.available()) {
            container.addMessageListener(this.listener(), new ChannelTopic(CHANNEL));
            log.info("事件广播已启用：channel={}", CHANNEL);
        }
    }

    private MessageListener listener() {
        return (Message message, byte[] pattern) -> {
            try {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                JsonNode root = Json.tree(body);
                long jobId = root.path("jobId").asLong(0);
                if (jobId > 0) {
                    wakeLocal(jobId);
                }
            } catch (RuntimeException e) {
                log.debug("解析事件广播失败: {}", e.getMessage());
            }
        };
    }

    /** 通知其他实例（以及本实例）某个任务有新事件 */
    public void publish(long jobId, long seq) {
        wakeLocal(jobId);
        if (!redis.available()) {
            return;
        }
        ObjectNode payload = Json.object();
        payload.put("jobId", jobId);
        payload.put("seq", seq);
        redis.publish(CHANNEL, Json.write(payload));
    }

    private void wakeLocal(long jobId) {
        List<Runnable> callbacks = waiters.get(jobId);
        if (callbacks == null) {
            return;
        }
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (RuntimeException e) {
                log.debug("唤醒 SSE 订阅者失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 注册一个等待者，返回注销句柄。
     * <p>SSE 每来一个连接就注册一次，断开时必须注销，否则 map 会一直增长。
     */
    public AutoCloseable subscribe(long jobId, Runnable wakeup) {
        CopyOnWriteArrayList<Runnable> list = waiters.computeIfAbsent(jobId, key -> new CopyOnWriteArrayList<>());
        list.add(wakeup);
        return () -> {
            list.remove(wakeup);
            if (list.isEmpty()) {
                waiters.remove(jobId);
            }
        };
    }

    /** 仅供测试与诊断：当前挂着的订阅数 */
    public int waiterCount() {
        return waiters.values().stream().mapToInt(List::size).sum();
    }
}
