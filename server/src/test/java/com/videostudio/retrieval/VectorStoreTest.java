package com.videostudio.retrieval;

import com.videostudio.domain.ContextChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 向量检索的纯逻辑部分：主键约定与内存实现 */
class VectorStoreTest {

    private static ContextChunk chunk(int index, List<Double> vector) {
        return new ContextChunk(index, index * 1000L, (index + 1) * 1000L, "摘要", List.of(),
                List.of(), "转写", "", vector, 0d);
    }

    @Test
    void keyRoundTripsThroughChunkIndexOf() {
        assertEquals(7, VectorStore.chunkIndexOf(VectorStore.key(42, 7)));
        assertEquals(0, VectorStore.chunkIndexOf(VectorStore.key(1, 0)));
        assertEquals(-1, VectorStore.chunkIndexOf("nonsense"));
        assertEquals(-1, VectorStore.chunkIndexOf(null));
    }

    @Test
    void keyEncodesJobSoTasksDoNotCollide() {
        // 不同任务的分块下标都从 0 开始，主键必须能把它们区分开
        assertFalse(VectorStore.key(1, 0).equals(VectorStore.key(2, 0)));
        // 但两者解析出来的分块下标相同，说明区分度完全来自前缀
        assertEquals(VectorStore.chunkIndexOf(VectorStore.key(1, 0)),
                VectorStore.chunkIndexOf(VectorStore.key(2, 0)));
        assertTrue(VectorStore.key(1, 0).contains("j-1-"));
    }

    @Test
    void memoryStoreReturnsCosinePerChunk() {
        MemoryVectorStore store = new MemoryVectorStore();
        List<ContextChunk> chunks = List.of(
                chunk(0, List.of(1.0, 0.0)),
                chunk(1, List.of(0.0, 1.0)));
        Map<Integer, Double> scores = store.semanticScores(1, chunks, List.of(1.0, 0.0));
        assertEquals(1.0, scores.get(0), 1e-9);
        assertEquals(0.0, scores.getOrDefault(1, 0d), 1e-9);
        // 与查询向量正交的分块不该进结果（0 分没有信息量，混进排序里会挤掉关键词命中）
        assertFalse(scores.containsKey(1));
    }

    @Test
    void memoryStoreIsDisabledByDesign() {
        // provider() 是给 /runtime 诊断用的，不能因为「没启用」就报错
        assertEquals("memory", new MemoryVectorStore().provider());
        assertFalse(new MemoryVectorStore().enabled());
    }

    @Test
    void emptyQueryVectorYieldsNoScores() {
        MemoryVectorStore store = new MemoryVectorStore();
        assertTrue(store.semanticScores(1, List.of(chunk(0, List.of(1.0))), List.of()).isEmpty());
    }

    @Test
    void toFloatsPreservesOrder() {
        float[] values = VectorStore.toFloats(List.of(0.5, -0.25, 1.0));
        assertEquals(0.5f, values[0], 1e-6);
        assertEquals(-0.25f, values[1], 1e-6);
        assertEquals(1.0f, values[2], 1e-6);
    }
}
