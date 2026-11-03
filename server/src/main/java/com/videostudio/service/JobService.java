package com.videostudio.service;

import com.videostudio.common.ApiException;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.JobStatus;
import com.videostudio.repository.JobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务调度：单机线程池 + 内存中的 cancel 句柄。
 * <p>demo 用进程内执行即可；换成队列时只需替换本类，流水线本身不感知。
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final AnalysisPipeline pipeline;
    private final ExecutorService executor;
    private final Map<Long, Future<?>> running = new ConcurrentHashMap<>();

    public JobService(JobRepository jobRepository, AnalysisPipeline pipeline) {
        this.jobRepository = jobRepository;
        this.pipeline = pipeline;
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "analysis-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public AnalysisJob submit(long assetId, AnalysisMode mode, String goal, String title) {
        String focus = goal == null || goal.isBlank() ? defaultGoal(mode) : goal.strip();
        long jobId = jobRepository.insert(assetId, mode, title, focus);
        submit(jobId, assetId);
        return jobRepository.find(jobId).orElseThrow();
    }

    /** 基于已有证据重跑分析（不重新转写/抽帧） */
    public AnalysisJob reanalyze(long jobId, String goal, AnalysisMode mode) {
        AnalysisJob old = jobRepository.find(jobId).orElseThrow(() -> ApiException.notFound("任务不存在"));
        if (old.status().running()) {
            throw ApiException.conflict("当前任务仍在执行，请稍后再试");
        }
        long newId = jobRepository.insert(old.assetId(), mode == null ? old.mode() : mode,
                old.title(), goal == null || goal.isBlank() ? old.focus() : goal.strip());
        submit(newId, old.assetId());
        return jobRepository.find(newId).orElseThrow();
    }

    private void submit(long jobId, long assetId) {
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
