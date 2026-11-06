package com.videostudio.infra;

import com.videostudio.common.ApiException;
import com.videostudio.config.BudgetProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 成本预算：单任务上限 + 每日总量 + 每日单用户上限。
 *
 * <p>为什么要有用户维度：日预算原本只有一个全站键，单个用户跑几部长视频就能把
 * 当天额度吃光，之后所有人都提交不了任务——这是把「成本控制」变成了「一个人拖垮所有人」。
 * 加上按用户的键之后，超额只影响超额的那个人。
 *
 * <p>用户维度是按<b>任务归属</b>反查的（{@link JobOwners}），因为模型调用发生在消费线程里，
 * 那里没有 SecurityContext。查不到归属时只检查全站额度，不把任务判死。
 */
@Component
public class BudgetGuard {

    private final BudgetProperties props;
    private final JobMetrics metrics;
    private final RedisSupport redis;
    private final JobOwners owners;
    private final ThreadLocal<Long> current = new ThreadLocal<>();

    public BudgetGuard(BudgetProperties props, JobMetrics metrics, RedisSupport redis, JobOwners owners) {
        this.props = props;
        this.metrics = metrics;
        this.redis = redis;
        this.owners = owners;
    }

    public boolean enabled() {
        return props.getMaxTokensPerJob() > 0 || props.getMaxTokensPerDay() > 0 || props.getMaxTokensPerUserPerDay() > 0;
    }

    public double costOf(long tokens) {
        return tokens / 1_000_000.0 * props.getPricePerMillionTokens();
    }

    /** 全站当日额度是否已用尽 */
    public String rejectReason() {
        return props.getMaxTokensPerDay() > 0 && dailyUsed() >= props.getMaxTokensPerDay()
                ? "今日模型配额已用尽，请明天再试" : null;
    }

    /** 指定用户当日额度是否已用尽；user=0 表示无法确定归属，不做判断 */
    public String rejectReason(long userId) {
        String global = rejectReason();
        if (global != null) {
            return global;
        }
        if (userId > 0 && props.getMaxTokensPerUserPerDay() > 0
                && dailyUsed(userId) >= props.getMaxTokensPerUserPerDay()) {
            return "你今天的模型配额已用尽，请明天再试";
        }
        return null;
    }

    public void begin(long jobId) {
        requireAvailable(jobId);
    }

    public void enter(long jobId) {
        current.set(jobId);
    }

    public void leave() {
        current.remove();
    }

    public long currentJob() {
        return current.get() == null ? 0 : current.get();
    }

    public void end(long jobId) {
    }

    public boolean consume(long jobId, int tokens) {
        if (tokens <= 0) {
            return false;
        }
        redis.add(dailyKey(), tokens, Duration.ofDays(2));
        if (props.getMaxTokensPerUserPerDay() > 0) {
            long userId = owners.ownerOf(jobId);
            if (userId > 0) {
                redis.add(dailyUserKey(userId), tokens, Duration.ofDays(2));
            }
        }
        long total = jobId > 0 ? redis.add(jobKey(jobId), tokens, Duration.ofDays(30)) : 0;
        boolean over = props.getMaxTokensPerJob() > 0 && total >= props.getMaxTokensPerJob();
        if (over) {
            metrics.markOverBudget();
        }
        return over;
    }

    public void consumeCurrent(int tokens) {
        consume(currentJob(), tokens);
    }

    public boolean exceeded(long jobId) {
        return jobId > 0 && props.getMaxTokensPerJob() > 0 && used(jobId) >= props.getMaxTokensPerJob();
    }

    public long used(long jobId) {
        return redis.get(jobKey(jobId)).orElse(0L);
    }

    public long dailyUsed() {
        return redis.get(dailyKey()).orElse(0L);
    }

    public long dailyUsed(long userId) {
        return userId <= 0 ? 0 : redis.get(dailyUserKey(userId)).orElse(0L);
    }

    /** 当前线程绑定的任务还能不能继续调模型 */
    public void requireAvailable() {
        requireAvailable(currentJob());
    }

    public void requireAvailable(long jobId) {
        String reason = rejectReason(owners.ownerOf(jobId));
        if (reason != null) {
            throw new ApiException(429, reason);
        }
        if (exceeded(jobId)) {
            throw new ApiException(429, "本任务的模型配额已用尽，分析已停止");
        }
    }

    private static String jobKey(long id) {
        return "video-studio:budget:job:" + id;
    }

    private static String dailyKey() {
        return "video-studio:budget:daily:" + LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    private static String dailyUserKey(long userId) {
        return "video-studio:budget:daily:user:" + userId + ":" + LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }
}
