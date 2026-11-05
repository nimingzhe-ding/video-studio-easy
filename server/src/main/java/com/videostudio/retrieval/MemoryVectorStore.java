package com.videostudio.retrieval;

import com.videostudio.domain.ContextChunk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认实现：全部在内存里算余弦。
 * <p>只对本次查询涉及的这些分块做暴力扫描，代价是 O(n·d)，n 通常是几十到几百，实测 600 块 49ms。
 */
public class MemoryVectorStore implements VectorStore {

    @Override
    public String provider() {
        return "memory";
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void index(long assetId, long jobId, List<ContextChunk> chunks) {
        // 向量本来就随分块存在数据库里，无需额外索引
    }

    @Override
    public Map<Integer, Double> semanticScores(long jobId, List<ContextChunk> chunks, List<Double> queryVector) {
        Map<Integer, Double> scores = new HashMap<>();
        if (queryVector == null || queryVector.isEmpty()) {
            return scores;
        }
        for (ContextChunk chunk : chunks) {
            double score = HybridRetriever.cosine(queryVector, chunk.embedding());
            if (score > 0) {
                scores.put(chunk.chunkIndex(), score);
            }
        }
        return scores;
    }

    @Override
    public void deleteJob(long jobId) {
        // 无外部状态
    }

    @Override
    public void deleteAsset(long assetId) {
        // 无外部状态
    }
}
