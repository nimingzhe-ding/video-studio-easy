package com.videostudio.infra;

import com.videostudio.config.AppProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 事件总线在单机（无 Redis、无监听容器）下的行为 */
class JobEventBusTest {

    private static JobEventBus bus() {
        RedisSupport redis = new RedisSupport(new AppProperties(), null);
        // 单机模式没有监听容器 bean，这里用空工厂模拟，验证构造器不会因此失败
        return new JobEventBus(redis, new DefaultListableBeanFactory().getBeanProvider(
                org.springframework.data.redis.listener.RedisMessageListenerContainer.class));
    }

    @Test
    void publishWakesSubscribersOfSameJob() {
        JobEventBus bus = bus();
        AtomicInteger wakeups = new AtomicInteger();
        try (AutoCloseable ignored = bus.subscribe(7, wakeups::incrementAndGet)) {
            bus.publish(7, 1);
            bus.publish(7, 2);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertEquals(2, wakeups.get(), "同实例的订阅者应被立刻唤醒");
    }

    @Test
    void otherJobsAreNotWoken() {
        JobEventBus bus = bus();
        AtomicInteger other = new AtomicInteger();
        try (AutoCloseable ignored = bus.subscribe(1, other::incrementAndGet)) {
            bus.publish(2, 1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertEquals(0, other.get(), "别的任务的事件不该唤醒这个订阅者");
    }

    @Test
    void unsubscribeRemovesWaiter() throws Exception {
        JobEventBus bus = bus();
        AtomicInteger wakeups = new AtomicInteger();
        AutoCloseable subscription = bus.subscribe(9, wakeups::incrementAndGet);
        assertEquals(1, bus.waiterCount());
        subscription.close();
        assertEquals(0, bus.waiterCount(), "SSE 断开后必须注销，否则 map 会一直增长");
        bus.publish(9, 1);
        assertEquals(0, wakeups.get());
    }

    @Test
    void throwingSubscriberDoesNotBreakOthers() {
        JobEventBus bus = bus();
        AtomicInteger good = new AtomicInteger();
        try (AutoCloseable ignored = bus.subscribe(3, () -> {
            throw new IllegalStateException("模拟回调炸了");
        }); AutoCloseable ignored2 = bus.subscribe(3, good::incrementAndGet)) {
            bus.publish(3, 1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertEquals(1, good.get(), "一个订阅者抛异常不能影响其他订阅者");
    }

    @Test
    void busWorksWithoutRedis() {
        JobEventBus bus = bus();
        assertTrue(bus.waiterCount() == 0);
        bus.publish(1, 1); // 不抛异常即通过
    }
}
