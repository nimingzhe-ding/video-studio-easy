package com.videostudio.web;

import com.videostudio.common.Json;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.JobEvent;
import com.videostudio.domain.JobStatus;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    public JobEventController(JobRepository jobRepository, JobEventRepository eventRepository) {
        this.jobRepository = jobRepository;
        this.eventRepository = eventRepository;
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

        PUMPS.scheduleWithFixedDelay(() -> {
            try {
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
            } catch (RuntimeException e) {
                log.debug("SSE 轮询任务 {} 失败: {}", id, e.getMessage());
            }
        }, 300, 700, TimeUnit.MILLISECONDS);

        emitter.onCompletion(() -> log.debug("SSE 任务 {} 结束", id));
        emitter.onTimeout(emitter::complete);
        return emitter;
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
    public Map<String, Object> runtime(@org.springframework.beans.factory.annotation.Autowired(required = false)
                                       com.videostudio.config.AiProperties ai,
                                       @org.springframework.beans.factory.annotation.Autowired(required = false)
                                       com.videostudio.config.RealtimeProperties realtime) {
        return Map.of(
                "aiEnabled", ai != null && ai.cloudEnabled(),
                "model", ai == null ? "" : ai.getModel(),
                "embeddingModel", ai == null ? "" : ai.getEmbeddingModel(),
                "realtimeEnabled", realtime != null && realtime.ready(),
                "status", JobStatus.values().length);
    }
}
