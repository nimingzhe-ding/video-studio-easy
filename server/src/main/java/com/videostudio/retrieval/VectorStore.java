package com.videostudio.retrieval;

import com.videostudio.domain.ContextChunk;

import java.util.List;
import java.util.Map;

/** Persistent vector retrieval contract. */
public interface VectorStore {

    String provider();

    boolean enabled();

    /**
     * 写入某个任务的分块向量。
     * <p>同一任务重复写入时先清掉旧数据——重跑分析不应该留下上一版的分块。
     */
    void index(long assetId, long jobId, List<ContextChunk> chunks);

    /**
     * 返回 {@code 分块下标 → 余弦相似度}。
     * <p>没有向量的分块不会出现在结果里，调用方按 0 处理。
     */
    Map<Integer, Double> semanticScores(long jobId, List<ContextChunk> chunks, List<Double> queryVector);

    void deleteJob(long jobId);

    void deleteAsset(long assetId);

    /** 把向量转成 float[]：浮点向量转换，转换点集中在实现里 */
    static float[] toFloats(List<Double> vector) {
        float[] values = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            values[i] = vector.get(i).floatValue();
        }
        return values;
    }

    /** 主键约定：一个任务里的分块下标唯一，跨任务靠 jobId 前缀区分 */
    static String key(long jobId, int chunkIndex) {
        return "j-%d-c-%d".formatted(jobId, chunkIndex);
    }

    /** 解析主键里的分块下标，格式不符返回 -1 */
    static int chunkIndexOf(String key) {
        if (key == null) {
            return -1;
        }
        int mark = key.lastIndexOf("-c-");
        if (mark < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring(mark + 3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
