package com.videostudio.infra;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标中心：把「跑得快不快、花了多少钱、降级了几次」变成可采集的数字。
 * <p>指标名遵循 Prometheus 约定（下划线、单位后缀），因此可以直接被
 * Grafana / Alertmanager 消费，而不需要额外写转换规则。
 */
@Component
public class JobMetrics {

    private final MeterRegistry registry;

    /** 当前排队 + 运行中的任务数，用于判断要不要扩容或限流 */
    private final AtomicInteger active = new AtomicInteger();
    /** 因超过预算被终止的任务数 */
    private final AtomicLong overBudget = new AtomicLong();
    /** 中间件不可用导致的降级次数（Redis / Milvus / MinIO） */
    private final Map<String, AtomicInteger> fallbacks = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> llmTokens = new ConcurrentHashMap<>();

    public JobMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("videostudio_jobs_active", active, AtomicInteger::get)
                .description("当前排队或运行中的分析任务数")
                .register(registry);
        Gauge.builder("videostudio_jobs_over_budget", overBudget, AtomicLong::get)
                .description("因超过成本预算而终止的任务数")
                .register(registry);
    }

    public void jobStarted(String mode) {
        active.incrementAndGet();
        Counter.builder("videostudio_jobs_started_total")
                .description("已启动的分析任务数")
                .tag("mode", mode)
                .register(registry)
                .increment();
    }

    public void jobFinished(String mode, String status, Duration elapsed) {
        active.decrementAndGet();
        Counter.builder("videostudio_jobs_finished_total")
                .description("已结束的分析任务数")
                .tag("mode", mode)
                .tag("status", status)
                .register(registry)
                .increment();
        Timer.builder("videostudio_job_duration_seconds")
                .description("分析任务从开始到结束的耗时")
                .tag("mode", mode)
                .tag("status", status)
                .register(registry)
                .record(elapsed);
    }

    /** 阶段耗时：能看出瓶颈在转写、抽帧还是模型生成 */
    public void stage(String stage, Duration elapsed) {
        Timer.builder("videostudio_stage_duration_seconds")
                .description("流水线各阶段耗时")
                .tag("stage", stage)
                .register(registry)
                .record(elapsed);
    }

    public void tokens(String model, int total) {
        llmTokens.computeIfAbsent(model, key -> new AtomicLong()).addAndGet(total);
        Counter.builder("videostudio_llm_tokens_total")
                .description("大模型消耗的 token 总数")
                .tag("model", model)
                .register(registry)
                .increment(total);
    }

    public void llmCall(String purpose, boolean success, Duration elapsed) {
        Timer.builder("videostudio_llm_call_duration_seconds")
                .description("单次大模型调用耗时")
                .tag("purpose", purpose)
                .tag("result", success ? "ok" : "fail")
                .register(registry)
                .record(elapsed);
    }

    /** 记录一次限流命中；标签用维度（submit / upload / chat）而不是具体 key，避免基数爆炸 */
    public void rateLimited(String scope) {
        Counter.builder("videostudio_rate_limited_total")
                .description("被限流拒绝的请求数")
                .tag("scope", scope)
                .register(registry)
                .increment();
    }

    public void fallback(String component, String reason) {
        fallbacks.computeIfAbsent(component, key -> new AtomicInteger()).incrementAndGet();
        Counter.builder("videostudio_fallback_total")
                .description("中间件不可用时的降级次数")
                .tag("component", component)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void markOverBudget() {
        overBudget.incrementAndGet();
    }

    public void segmentCount(String kind, int count) {
        DistributionSummary.builder("videostudio_evidence_count")
                .description("证据数量分布（转写段 / 画面条 / 分块）")
                .tag("kind", kind)
                .register(registry)
                .record(count);
    }
}
