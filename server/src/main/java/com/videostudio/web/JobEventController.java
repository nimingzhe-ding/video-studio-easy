package com.videostudio.web;

import com.videostudio.common.ApiResponse;
import com.videostudio.common.Json;
import com.videostudio.config.AiProperties;
import com.videostudio.config.RealtimeProperties;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.JobEvent;
import com.videostudio.domain.JobStatus;
import com.videostudio.infra.JobEventBus;
import com.videostudio.infra.RedisSupport;
import com.videostudio.retrieval.VectorStore;
import com.videostudio.storage.MediaObjects;
import com.videostudio.repository.JobEventRepository;
import com.videostudio.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 推送：先回放历史事件，再轮询新事件直到任务终止。
 * <p>用「事件表 + 轮询」而不是内存 pub/sub，刷新页面、断线重连都能补齐进度，
 * 也不会因为内存订阅丢失而让前端卡在 90%。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobEventController {

    private static final Logger log = LoggerFactory.getLogger(JobEventController.class);
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final ScheduledExecutorService PUMPS = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "sse-pump");
        t.setDaemon(true);
        return t;
    });

    private final JobRepository jobRepository;
    private final JobEventRepository eventRepository;
    private final AiProperties ai;
    private final RealtimeProperties realtime;
    private final MediaObjects objects;
    private final VectorStore vectors;
    private final JobEventBus eventBus;
    private final RedisSupport redis;

    public JobEventController(JobRepository jobRepository, JobEventRepository eventRepository,
                              AiProperties ai, RealtimeProperties realtime,
                              MediaObjects objects, VectorStore vectors,
                              JobEventBus eventBus, RedisSupport redis) {
        this.jobRepository = jobRepository;
        this.eventRepository = eventRepository;
        this.ai = ai;
        this.realtime = realtime;
        this.objects = objects;
        this.vectors = vectors;
        this.eventBus = eventBus;
        this.redis = redis;
    }

    @GetMapping("/{id}/stream")
    public SseEmitter stream(@PathVariable long id, @RequestParam(defaultValue = "0") long after) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicLong cursor = new AtomicLong(after);

        send(emitter, "hello", Map.of("jobId", id, "after", after));

        // 立即回放已有事件，前端无需再额外拉一次
        List<JobEvent> history = eventRepository.list(id, after, 1000);
        for (JobEvent event : history) {
            if (!send(emitter, "event", event)) {
                return emitter;
            }
            cursor.set(Math.max(cursor.get(), event.seq()));
        }
        AnalysisJob job = jobRepository.find(id).orElse(null);
        if (job != null && job.status().terminal()) {
            send(emitter, "done", Map.of("status", job.status().name(), "progress", job.progress()));
            emitter.complete();
            return emitter;
        }

        // 事件写入时会广播唤醒，所以这里不必高频轮询：
        // 正常情况被外部唤醒（毫秒级），信号丢失时靠 3 秒一次的兜底轮询补上。
        AtomicBoolean closed = new AtomicBoolean(false);
        Semaphore signal = new Semaphore(0);
        AutoCloseable subscription = eventBus.subscribe(id, signal::release);

        PUMPS.scheduleWithFixedDelay(() -> {
            if (closed.get()) {
                return;
            }
            try {
                // 有信号就立刻醒；没有就最多等 3 秒（兜底，防止广播丢失）
                signal.tryAcquire(3, TimeUnit.SECONDS);
                signal.drainPermits();
                List<JobEvent> fresh = eventRepository.list(id, cursor.get(), 200);
                for (JobEvent event : fresh) {
                    if (!send(emitter, "event", event)) {
                        emitter.complete();
                        return;
                    }
                    cursor.set(event.seq());
                }
                AnalysisJob current = jobRepository.find(id).orElse(null);
                if (current != null && current.status().terminal()) {
                    send(emitter, "done", Map.of("status", current.status().name(),
                            "progress", current.progress(),
                            "error", current.error() == null ? "" : current.error()));
                    emitter.complete();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.debug("SSE 轮询任务 {} 失败: {}", id, e.getMessage());
            }
        }, 200, 100, TimeUnit.MILLISECONDS);

        emitter.onCompletion(() -> {
            closed.set(true);
            closeQuietly(subscription);
            log.debug("SSE 任务 {} 结束", id);
        });
        emitter.onTimeout(() -> {
            closed.set(true);
            closeQuietly(subscription);
            emitter.complete();
        });
        emitter.onError(e -> {
            closed.set(true);
            closeQuietly(subscription);
        });
        return emitter;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // 注销订阅失败不影响连接关闭
        }
    }

    private boolean send(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(Json.write(payload)));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    /** 轻量健康检查，前端用来确认后端在线与 AI 是否已配置 */
    @GetMapping("/runtime")
    public ApiResponse<Map<String, Object>> runtime() {
        // 必须和其余接口一样包一层 ApiResponse：前端 request() 统一取 payload.data，
        // 返回裸 Map 会让整块环境信息在前端变成 undefined（表现为永远显示「离线模式」）。
        // Map.of 最多十对键值，字段再多就得换成有序 Map（顺便保证输出顺序稳定，便于比对）
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("aiEnabled", ai.cloudEnabled());
        info.put("model", ai.getModel());
        info.put("embeddingModel", ai.getEmbeddingModel());
        info.put("visionCaptionEnabled", ai.visionCaptionEnabled());
        info.put("visionModel", ai.getVisionModel());
        info.put("realtimeEnabled", realtime.ready());
        // 诊断用：把当前生效的实现暴露给前端，避免「以为接了对象存储/向量库其实没接」
        info.put("objectStorage", objects.store().provider());
        info.put("objectStorageEnabled", objects.enabled());
        info.put("vectorStore", vectors.provider());
        info.put("vectorStoreEnabled", vectors.enabled());
        info.put("redisEnabled", redis.available());
        info.put("status", JobStatus.values().length);
        return ApiResponse.success(info);
    }
}
