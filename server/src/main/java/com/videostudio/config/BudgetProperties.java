package com.videostudio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 成本预算。
 * <p>单位是 token 而不是请求次数：一次请求可能是 200 token 的分块摘要，
 * 也可能是 8000 token 的长上下文执行，按次数限制根本挡不住真正的花费。
 */
@ConfigurationProperties(prefix = "budget")
public class BudgetProperties {

    /** 单任务 token 上限，0 表示不限制（默认 200 万，够一次 2 小时视频的多轮分析） */
    private long maxTokensPerJob = 2_000_000;
    /** 每日总量上限（跨任务、跨用户累加），0 表示不限制 */
    private long maxTokensPerDay = 20_000_000;
    /**
     * 每日单用户上限，0 表示不限制。
     * <p>默认 500 万，约等于全站额度的四分之一：既能让单个用户跑完一整批分析，
     * 又不至于一个人把当天额度吃光、让其他人提交不了任务。
     */
    private long maxTokensPerUserPerDay = 5_000_000;
    /** 每百万 token 单价，仅用于日志与估算，不影响任何逻辑 */
    private double pricePerMillionTokens = 2.0;

    public long getMaxTokensPerJob() {
        return maxTokensPerJob;
    }

    public void setMaxTokensPerJob(long maxTokensPerJob) {
        this.maxTokensPerJob = maxTokensPerJob;
    }

    public long getMaxTokensPerDay() {
        return maxTokensPerDay;
    }

    public void setMaxTokensPerDay(long maxTokensPerDay) {
        this.maxTokensPerDay = maxTokensPerDay;
    }

    public long getMaxTokensPerUserPerDay() {
        return maxTokensPerUserPerDay;
    }

    public void setMaxTokensPerUserPerDay(long maxTokensPerUserPerDay) {
        this.maxTokensPerUserPerDay = maxTokensPerUserPerDay;
    }

    public double getPricePerMillionTokens() {
        return pricePerMillionTokens;
    }

    public void setPricePerMillionTokens(double pricePerMillionTokens) {
        this.pricePerMillionTokens = pricePerMillionTokens;
    }
}
