package com.videostudio.domain;

import java.time.Instant;

/** 分析任务 */
public record AnalysisJob(
        long id,
        long assetId,
        AnalysisMode mode,
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
}
