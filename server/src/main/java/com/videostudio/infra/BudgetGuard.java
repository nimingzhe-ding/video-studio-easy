package com.videostudio.infra;

import com.videostudio.common.ApiException;
import com.videostudio.config.BudgetProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class BudgetGuard {
    private final BudgetProperties props; private final JobMetrics metrics; private final RedisSupport redis;
    private final ThreadLocal<Long> current=new ThreadLocal<>();
    public BudgetGuard(BudgetProperties props,JobMetrics metrics,RedisSupport redis) { this.props=props; this.metrics=metrics; this.redis=redis; }
    public boolean enabled() { return props.getMaxTokensPerJob()>0 || props.getMaxTokensPerDay()>0; }
    public double costOf(long tokens) { return tokens/1_000_000.0*props.getPricePerMillionTokens(); }
    public String rejectReason() {
        return props.getMaxTokensPerDay()>0 && dailyUsed()>=props.getMaxTokensPerDay() ? "今日模型配额已用尽，请明天再试" : null;
    }
    public void begin(long jobId) { requireAvailable(); }
    public void enter(long jobId) { current.set(jobId); }
    public void leave() { current.remove(); }
    public long currentJob() { return current.get()==null?0:current.get(); }
    public void end(long jobId) {}
    public boolean consume(long jobId,int tokens) {
        if(tokens<=0) return false;
        redis.add(dailyKey(),tokens,Duration.ofDays(2));
        long total=jobId>0?redis.add(jobKey(jobId),tokens,Duration.ofDays(30)):0;
        boolean over=props.getMaxTokensPerJob()>0 && total>=props.getMaxTokensPerJob();
        if(over) metrics.markOverBudget();
        return over;
    }
    public void consumeCurrent(int tokens) { consume(currentJob(),tokens); }
    public boolean exceeded(long jobId) { return jobId>0 && props.getMaxTokensPerJob()>0 && used(jobId)>=props.getMaxTokensPerJob(); }
    public long used(long jobId) { return redis.get(jobKey(jobId)).orElse(0L); }
    public long dailyUsed() { return redis.get(dailyKey()).orElse(0L); }
    public void requireAvailable() {
        String reason=rejectReason();
        if(reason!=null) throw new ApiException(429,reason);
        if(exceeded(currentJob())) throw new ApiException(429,"本任务的模型配额已用尽，分析已停止");
    }
    private static String jobKey(long id) { return "video-studio:budget:job:"+id; }
    private static String dailyKey() { return "video-studio:budget:daily:"+LocalDate.now(ZoneId.of("Asia/Shanghai")); }
}
