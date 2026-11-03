package com.videostudio.domain;

import java.time.Instant;

/** 生成好的分析报告 */
public record AnalysisReport(
        long id,
        long jobId,
        long assetId,
        String title,
        AnalysisMode mode,
        String markdown,
        String outlineJson,
        String traceJson,
        String docxPath,
        Instant createdAt
) {
}
