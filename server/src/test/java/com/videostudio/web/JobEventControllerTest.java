package com.videostudio.web;

import com.videostudio.config.AiProperties;
import com.videostudio.config.RealtimeProperties;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.JobStage;
import com.videostudio.domain.JobStatus;
import com.videostudio.infra.JobEventBus;
import com.videostudio.infra.RedisSupport;
import com.videostudio.repository.JobEventRepository;
import com.videostudio.repository.JobRepository;
import com.videostudio.retrieval.VectorStore;
import com.videostudio.storage.MediaObjects;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SSE 推送的退出行为。
 *
 * <p>泵线程必须在连接关闭后停止：修复前它跑在固定大小的调度池里且从不取消，
 * 每个关掉的连接都留下一个每 100ms 唤一次的空转任务，长期运行会累积成 CPU 与日志噪声。
 */
class JobEventControllerTest {

    private final JobRepository jobs = mock(JobRepository.class);
    private final JobEventRepository events = mock(JobEventRepository.class);
    private final JobEventBus bus = mock(JobEventBus.class);

    private JobEventController controller() {
        return new JobEventController(jobs, events, mock(AiProperties.class), mock(RealtimeProperties.class),
                mock(MediaObjects.class), mock(VectorStore.class), bus, mock(RedisSupport.class));
    }

    private AnalysisJob runningJob() {
        return new AnalysisJob(1, 5, AnalysisMode.GENERAL, "USER", null, null, "目标",
                JobStatus.RUNNING, JobStage.TRANSCRIBE, 30, null, false, null,
                Instant.now(), Instant.now(), null);
    }

    private static void awaitAtLeast(AtomicInteger counter, int target, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (counter.get() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void pumpStopsAfterTheEmitterCompletes() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicReference<Runnable> wake = new AtomicReference<>();
        when(events.list(eq(1L), anyLong(), any(Integer.class))).thenAnswer(invocation -> {
            polls.incrementAndGet();
            return List.of();
        });
        when(jobs.find(1)).thenReturn(java.util.Optional.of(runningJob()));
        when(bus.subscribe(eq(1L), any())).thenAnswer(invocation -> {
            wake.set(invocation.getArgument(1));
            return (AutoCloseable) () -> { };
        });

        SseEmitter emitter = controller().stream(1, 0);

        // 唤醒两次，确认泵线程确实在工作（不是一启动就退出了）
        wake.get().run();
        awaitAtLeast(polls, 1, 2_000);
        wake.get().run();
        awaitAtLeast(polls, 2, 2_000);
        assertTrue(polls.get() >= 2, "泵线程应当被事件广播唤醒并轮询新事件");

        emitter.complete();
        int settled = polls.get();
        Thread.sleep(150);
        assertEquals(settled, polls.get(), "连接关闭后泵线程必须立刻停止，不再继续查库");
    }
}
