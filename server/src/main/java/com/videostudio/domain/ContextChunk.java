package com.videostudio.domain;

import java.util.List;

/**
 * 检索出的上下文分块。score 由语义相似度与词项命中率混合而成。
 */
public record ContextChunk(
        int chunkIndex,
        long startMs,
        long endMs,
        String summary,
        List<String> keywords,
        List<String> visualKeywords,
        String transcriptText,
        String ocrText,
        List<Double> embedding,
        double score
) {
    public String window() {
        return com.videostudio.common.Times.format(startMs) + " - " + com.videostudio.common.Times.format(endMs);
    }
}
