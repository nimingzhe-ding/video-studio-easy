package com.videostudio.service;

import com.videostudio.common.ApiException;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.JobStatus;
import com.videostudio.infra.BudgetGuard;
import com.videostudio.infra.JobMetrics;
import com.videostudio.infra.RedisSupport;
import com.videostudio.repository.CheckpointRepository;
import com.videostudio.repository.JobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务调度：单机线程池 + 内存中的 cancel 句柄。
 * <p>demo 用进程内执行即可；换成队列时只需替换本类，流水线本身不感知。
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    /** 同时执行的分析任务数：每个任务都会跑 ffmpeg 与本地模型，开太多反而互相抢 CPU */
    private static final int ANALYSIS_THREADS = 2;
    /** 排队上限：再多的请求应该让用户等一会儿再试，而不是无限堆在内存里 */
    private static final int QUEUE_CAPACITY = 32;

    private final JobRepository jobRepository;
    private final AnalysisPipeline pipeline;
    private final ModeRouter modeRouter;
    private final RedisSupport redis;
    private final BudgetGuard budget;
    private final JobMetrics metrics;
    private final CheckpointRepository checkpoints;
    private final ExecutorService executor;
    private final Map<Long, Future<?>> running = new ConcurrentHashMap<>();

    public JobService(JobRepository jobRepository, AnalysisPipeline pipeline, ModeRouter modeRouter,
                      RedisSupport redis, BudgetGuard budget, JobMetrics metrics,
                      CheckpointRepository checkpoints) {
        this.jobRepository = jobRepository;
        this.pipeline = pipeline;
        this.modeRouter = modeRouter;
        this.redis = redis;
        this.budget = budget;
        this.metrics = metrics;
        this.checkpoints = checkpoints;
        AtomicInteger counter = new AtomicInteger();
        // 有界队列 + 显式拒绝，而不是无界队列：无界队列在提交速度超过处理速度时
        // 会一直堆积，最终 OOM 且没有任何提示；有界队列则立刻返回 429，让调用方知道要等。
        this.executor = new ThreadPoolExecutor(
                ANALYSIS_THREADS, ANALYSIS_THREADS,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "analysis-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 提交分析任务。
     *
     * @param mode 为 null 或 AUTO 时走 {@link ModeRouter} 自动判定
     */
    public AnalysisJob submit(long assetId, AnalysisMode mode, String goal, String title) {
        // 先查额度：超了就别建任务，否则会先花钱跑完转写与抽帧再失败
        String rejected = budget.rejectReason();
        if (rejected != null) {
            throw ApiException.conflict(rejected);
        }
        RouteResult decision = resolveMode(mode, goal);
        String focus = goal == null || goal.isBlank() ? defaultGoal(decision.mode()) : goal.strip();
        // 同一个素材 + 同一个目标，短时间内重复提交只保留第一个。
        // 用「素材 + 目标摘要」而不是「素材」：用户明确要换个角度分析两次，是合法需求。
        String key = dedupeKey(assetId, focus);
        Optional<String> token = redis.tryLock(key, redis.lockTtl());
        boolean locked = token.isPresent();
        try {
            // 有 Redis 时靠跨实例锁保证多副本只有一个能进；
            // 没有 Redis 时退化为数据库检查（只能挡住单实例内的双击）
            if (!locked && redis.configured() && redis.available()) {
                throw ApiException.conflict("同一素材的相同目标正在提交，请稍候");
            }
            long jobId = jobRepository.insert(assetId, decision.mode(), decision.source(), decision.reason(),
                    title, focus);
            submit(jobId, assetId);
            return jobRepository.find(jobId).orElseThrow();
        } finally {
            token.ifPresent(value -> redis.unlock(key, value));
        }
    }

    /** 幂等键：素材 + 目标，前缀故意写成 lock: 以便运维一眼看出这是锁而不是缓存 */
    private static String dedupeKey(long assetId, String focus) {
        return "lock:submit:" + assetId + ":" + Integer.toHexString(focus.hashCode());
    }

    /** 基于已有证据重跑分析（不重新转写/抽帧） */
    public AnalysisJob reanalyze(long jobId, String goal, AnalysisMode mode) {
        AnalysisJob old = jobRepository.find(jobId).orElseThrow(() -> ApiException.notFound("任务不存在"));
        if (old.status().running()) {
            throw ApiException.conflict("当前任务仍在执行，请稍后再试");
        }
        String focus = goal == null || goal.isBlank() ? old.focus() : goal.strip();
        // 重跑未指定模式时沿用原任务模式（而不是重新路由），避免「换个目标」意外改掉文档结构
        RouteResult decision = mode == null
                ? new RouteResult(old.mode(),
                        old.modeSource() == null ? AnalysisJob.MODE_SOURCE_USER : old.modeSource(),
                        old.modeReason())
                : new RouteResult(mode, AnalysisJob.MODE_SOURCE_USER, null);
        long newId = jobRepository.insert(old.assetId(), decision.mode(), decision.source(), decision.reason(),
                old.title(), focus);
        submit(newId, old.assetId());
        return jobRepository.find(newId).orElseThrow();
    }

    /**
     * 用户显式指定模式时直接采用（来源 USER）；未指定或指定 AUTO 时交给路由。
     * <p>路由永远返回具体模式，因此落库的 mode 不会是「待定」。
     */
    private RouteResult resolveMode(AnalysisMode mode, String goal) {
        if (mode != null) {
            return new RouteResult(mode, AnalysisJob.MODE_SOURCE_USER, null);
        }
        ModeRouter.Decision routed = modeRouter.route(goal);
        return new RouteResult(routed.mode(), routed.byModel() ? AnalysisJob.MODE_SOURCE_AUTO
                : AnalysisJob.MODE_SOURCE_RULE, routed.reason());
    }

    /** 路由结果 + 来源标记，避免在调用点重复拼字符串 */
    private record RouteResult(AnalysisMode mode, String source, String reason) {
    }

    private void submit(long jobId, long assetId) {
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    pipeline.run(jobId);
                } catch (Throwable t) {
                    log.error("任务 {} 异常退出", jobId, t);
                } finally {
                    running.remove(jobId);
                }
            });
            running.put(jobId, future);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 队列满了：把任务标记失败并给出可操作的提示，而不是让用户对着「排队中」干等
            String message = "分析队列已满（%d 个任务在排队），请稍后重试".formatted(QUEUE_CAPACITY);
            jobRepository.markFailed(jobId, message);
            log.warn("任务 {} 被拒绝：队列已满", jobId);
            throw ApiException.conflict(message);
        }
    }

    /**
     * 失败重试。
     * <p>与「重新分析」的区别：重新分析会新建任务、重新路由模式；重试是接着原任务走，
     * 已完成的阶段（尤其是转写与抽帧）由断点与证据复用带过来，只补没做完的部分。
     * <p>只允许重试失败或已取消的任务：对正在跑的任务重复调用会把同一条流水线跑两遍。
     */
    public AnalysisJob retry(long jobId) {
        AnalysisJob job = jobRepository.find(jobId)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + jobId));
        if (job.status() == JobStatus.SUCCEEDED) {
            throw ApiException.conflict("任务已成功完成，如需换角度请用重新分析");
        }
        if (!job.status().terminal()) {
            throw ApiException.conflict("任务仍在运行中，请先等待或取消");
        }
        String rejected = budget.rejectReason();
        if (rejected != null) {
            throw ApiException.conflict(rejected);
        }
        jobRepository.retry(jobId);
        submit(jobId, job.assetId());
        log.info("任务 {} 进入重试", jobId);
        return jobRepository.find(jobId).orElseThrow();
    }

    public boolean cancel(long jobId) {
        Future<?> future = running.get(jobId);
        Optional<AnalysisJob> job = jobRepository.find(jobId);
        if (job.isEmpty()) {
            throw ApiException.notFound("任务不存在");
        }
        if (job.get().status() != JobStatus.RUNNING && job.get().status() != JobStatus.PENDING) {
            return false;
        }
        jobRepository.markCanceled(jobId);
        if (future != null) {
            future.cancel(true);
            running.remove(jobId);
        }
        return true;
    }

    private String defaultGoal(AnalysisMode mode) {
        return switch (mode) {
            case LEARNING -> "把这段内容整理成可复习的学习笔记，覆盖核心概念、例子与易错点";
            case REVIEW -> "整理成评审纪要：议题、各方观点、结论、待办与风险";
            case CREATION -> "提炼可用于二次创作的素材：金句、钩子、结构节奏";
            case GENERAL -> "总结这段内容讲了什么，给出关键结论与重点片段时间戳";
        };
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
