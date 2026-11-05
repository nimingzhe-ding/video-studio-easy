package com.videostudio.infra;

import com.videostudio.config.BudgetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成本护栏：一次分析最多花多少 token、一天最多花多少。
 * <p>为什么必须有：长视频一次分析可能触发几十次模型调用（分块摘要 + 规划 + 多轮执行 + 校验），
 * 一旦提示词写错导致循环调用，一张卡刷掉几百块是真实发生过的事故。
 * <p>分两层：<b>单任务</b>超限立刻终止并给出明确错误；<b>每日总量</b>超限时新任务直接拒绝提交，
 * 而不是跑到一半才失败——后者会浪费已经花掉的转写与抽帧成本。
 */
@Component
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);

    private final BudgetProperties props;
    private final JobMetrics metrics;
    private final RedisSupport redis;

    /** jobId -> 已消耗 token */
    private final Map<Long, AtomicLong> perJob = new ConcurrentHashMap<>();
    /** 没有 Redis 时的本机日累计；有 Redis 时以 Redis 为准，保证多实例共享同一个额度 */
    private final AtomicLong localDaily = new AtomicLong();
    /**
     * 当前线程正在记账的任务。
     * <p>模型调用发生在流水线线程上（分块摘要、规划、执行、校验都在同一条线程），
     * 所以用 ThreadLocal 传递 jobId 比给每一层方法都加一个 jobId 参数干净得多。
     * 代价是 OCR 池线程里的画面描述不计入——它受帧数配额限制，量级可忽略。
     */
    private final ThreadLocal<Long> current = new ThreadLocal<>();
    /** 已经超限的任务，供 Agent 循环判断是否还要继续请求模型 */
    private final Set<Long> exhausted = ConcurrentHashMap.newKeySet();

    public BudgetGuard(BudgetProperties props, JobMetrics metrics, RedisSupport redis) {
        this.props = props;
        this.metrics = metrics;
        this.redis = redis;
    }

    public boolean enabled() {
        return props.getMaxTokensPerJob() > 0 || props.getMaxTokensPerDay() > 0;
    }

    /** 估算成本（美元）；按配置的每百万 token 单价算，供日志与指标使用 */
    public double costOf(long tokens) {
        return tokens / 1_000_000.0 * props.getPricePerMillionTokens();
    }

    /**
     * 提交前的额度检查。
     *
     * @return 超限时返回原因，可用时返回 null
     */
    public String rejectReason() {
        if (props.getMaxTokensPerDay() <= 0) {
            return null;
        }
        long used = dailyUsed();
        if (used >= props.getMaxTokensPerDay()) {
            return "今日模型配额已用尽（%d / %d token），请明天再试或调高 BUDGET_MAX_TOKENS_PER_DAY"
                    .formatted(used, props.getMaxTokensPerDay());
        }
        return null;
    }

    /** 任务开始时清零计数，避免同一 jobId 被重试时累加两次 */
    public void begin(long jobId) {
        perJob.put(jobId, new AtomicLong());
        exhausted.remove(jobId);
    }

    /** 进入任务上下文；必须在 finally 里 {@link #leave()} */
    public void enter(long jobId) {
        current.set(jobId);
    }

    public void leave() {
        current.remove();
    }

    /** 当前线程绑定的任务 id；没有绑定时返回 0 */
    public long currentJob() {
        Long jobId = current.get();
        return jobId == null ? 0 : jobId;
    }

    public void end(long jobId) {
        exhausted.remove(jobId);
        AtomicLong used = perJob.remove(jobId);
        if (used != null && used.get() > 0) {
            log.info("任务 {} 共消耗 {} token，约 ${}",
                    jobId, used.get(), String.format(java.util.Locale.ROOT, "%.4f", costOf(used.get())));
        }
    }

    /**
     * 记录一次调用的 token 消耗。
     *
     * @return true 表示已经超限，调用方应停止继续请求模型
     */
    public boolean consume(long jobId, int tokens) {
        if (tokens <= 0) {
            return false;
        }
        AtomicLong counter = perJob.computeIfAbsent(jobId, key -> new AtomicLong());
        long total = counter.addAndGet(tokens);
        localDaily.addAndGet(tokens);
        if (redis.configured()) {
            redis.increment(dailyKey(), Duration.ofHours(26));
        }
        metrics.tokens("all", tokens);
        if (props.getMaxTokensPerJob() > 0 && total > props.getMaxTokensPerJob()) {
            Boolean first = exhausted.add(jobId);
            if (Boolean.TRUE.equals(first)) {
                metrics.markOverBudget();
            }
            log.warn("任务 {} 超出单任务预算：{} > {} token", jobId, total, props.getMaxTokensPerJob());
            return true;
        }
        return false;
    }

    /** 供 LlmGateway 在每次调用后记账，无需知道 jobId */
    public void consumeCurrent(int tokens) {
        Long jobId = current.get();
        if (jobId != null) {
            consume(jobId, tokens);
        }
    }

    /** 任务是否已经超限；Agent 循环据此提前收敛，而不是等模型调用被拒绝 */
    public boolean exceeded(long jobId) {
        return exhausted.contains(jobId);
    }

    public long used(long jobId) {
        AtomicLong counter = perJob.get(jobId);
        return counter == null ? 0 : counter.get();
    }

    public long dailyUsed() {
        // 有 Redis 时全局共享（多实例共用一个额度），没有时退化成进程内计数
        return redis.get(dailyKey()).orElseGet(localDaily::get);
    }

    private static String dailyKey() {
        return "video-studio:budget:" + LocalDate.now();
    }
}
