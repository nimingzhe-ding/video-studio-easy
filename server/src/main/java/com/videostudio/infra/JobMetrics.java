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
 * 指标中心：把任务耗时、模型用量与失败率变成可采集的数字。
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
    private final Map<String, AtomicLong> llmTokens = new ConcurrentHashMap<>();
    /** 发件箱积压：投递器每轮刷新一次，供 Prometheus 抓取 */
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestAge = new AtomicLong();
    private final AtomicLong failedDelivery = new AtomicLong();
    private final AtomicLong invalidMessagesPending = new AtomicLong();

    public JobMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("videostudio_jobs_active", active, AtomicInteger::get)
                .description("当前排队或运行中的分析任务数")
                .register(registry);
        Gauge.builder("videostudio_jobs_over_budget", overBudget, AtomicLong::get)
                .description("因超过成本预算而终止的任务数")
                .register(registry);
        Gauge.builder("videostudio_outbox_pending", outboxPending, AtomicLong::get)
                .description("尚未被 Broker 确认的发件箱记录数")
                .register(registry);
        Gauge.builder("videostudio_outbox_oldest_age_seconds", outboxOldestAge, AtomicLong::get)
                .description("最老的未投递发件箱记录年龄，积压告警用它")
                .register(registry);
        Gauge.builder("videostudio_failed_delivery_total", failedDelivery, AtomicLong::get)
                .description("失败投递台账记录数，含永久失败与异常消息")
                .register(registry);
        Gauge.builder("videostudio_invalid_messages_pending",invalidMessagesPending,AtomicLong::get)
                .description("尚未排查处理的异常队列消息数").register(registry);
    }

    /** 刷新发件箱积压指标。参数由投递器在每轮扫描时用一条聚合查询算出 */
    public void outbox(long pending, long oldestAgeSeconds, long failed) {
        outboxPending.set(pending);
        outboxOldestAge.set(oldestAgeSeconds);
        failedDelivery.set(failed);
    }

    public void invalidMessagesPending(long count) { invalidMessagesPending.set(count); }

    /** 记录一次任务重新排队；reason=stale 表示执行者失联，gave_up 表示放弃自动重跑 */
    public void jobRequeued(String reason) {
        Counter.builder("videostudio_jobs_requeued_total")
                .description("被重新排队的任务数")
                .tag("reason", reason)
                .register(registry)
                .increment();
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


    public void retrievalFallback(String mode) {
        Counter.builder("videostudio_retrieval_fallback_total").tag("mode",mode).register(registry).increment();
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
