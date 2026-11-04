package com.videostudio.domain;

import java.time.Instant;

/**
 * 分析任务。
 *
 * @param mode       最终落定的分析模式（永远是四个具体模式之一，不会是「待路由」）
 * @param modeSource 模式来源：USER 用户指定 / AUTO 模型路由 / RULE 关键词兜底
 * @param modeReason 模式判定理由，前端可直接展示
 */
public record AnalysisJob(
        long id,
        long assetId,
        AnalysisMode mode,
        String modeSource,
        String modeReason,
        String title,
        String focus,
        JobStatus status,
        JobStage stage,
        int progress,
        String error,
        boolean degraded,
        String notices,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt
) {
    public static final String MODE_SOURCE_USER = "USER";
    public static final String MODE_SOURCE_AUTO = "AUTO";
    public static final String MODE_SOURCE_RULE = "RULE";
}
