package com.videostudio.domain;

import java.time.Instant;

/**
 * 分析任务。
 *
 * @param mode       最终落定的分析模式（永远是四个具体模式之一，不会是「待路由」）
 * @param modeSource 模式来源：USER 用户指定 / AUTO 模型路由。判定失败时提交直接报错，
 *                   不存在按关键词规则猜一个的路径
 * @param modeReason 模式判定理由，前端可直接展示
 * @param degraded   是否降级完成。当前采集链路（ASR/OCR/向量索引）失败会直接终止任务，
 *                   所以正常路径上恒为 false；保留字段是为了让「部分证据缺失但报告可用」
 *                   这类未来策略不需要改表结构
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
}
