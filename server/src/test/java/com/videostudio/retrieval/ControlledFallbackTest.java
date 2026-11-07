package com.videostudio.retrieval;

import com.videostudio.config.AgentProperties;
import com.videostudio.domain.ContextChunk;
import com.videostudio.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlledFallbackTest {
    private ContextChunk chunk(List<Double> embedding) {
        return new ContextChunk(0,0,1000,"测试目标",List.of(),List.of(),"测试目标","",embedding,0);
    }
    @Test void normalRequestsNeverUseFallback() {
        var llm=mock(LlmGateway.class); var store=mock(VectorStore.class);
        when(llm.embed(anyString())).thenReturn(List.of(1d,0d));
        when(store.semanticScores(anyLong(),anyList(),anyList())).thenReturn(Map.of(0,1d));
        var hits=new HybridRetriever(llm,new AgentProperties(),store).search(1,"测试目标",List.of(chunk(List.of(1d,0d))),List.of(),List.of());
        assertEquals("qdrant",hits.get(0).retrievalMode());
        assertEquals("",HybridRetriever.fallbackNotice(hits));
        verify(store,times(1)).semanticScores(anyLong(),anyList(),anyList());
    }
    @Test void transientOutageRecoversBeforeAnyFallback() {
        var llm=mock(LlmGateway.class); var store=mock(VectorStore.class);
        when(llm.embed(anyString())).thenReturn(List.of(1d,0d));
        when(store.semanticScores(anyLong(),anyList(),anyList())).thenThrow(new IllegalStateException("network")).thenReturn(Map.of(0,1d));
        var hits=new HybridRetriever(llm,new AgentProperties(),store).search(1,"测试目标",List.of(chunk(List.of(1d,0d))),List.of(),List.of());
        assertEquals("qdrant",hits.get(0).retrievalMode());
    }
    @Test void savedVectorsAreUsedAfterRetriesAndNextRequestReturnsToQdrant() {
        var llm=mock(LlmGateway.class); var store=mock(VectorStore.class);
        when(llm.embed(anyString())).thenReturn(List.of(1d,0d));
        when(store.semanticScores(anyLong(),anyList(),anyList())).thenThrow(new IllegalStateException("network"));
        var retriever=new HybridRetriever(llm,new AgentProperties(),store);
        var chunks=List.of(chunk(List.of(1d,0d)));
        var degraded=retriever.search(1,"测试目标",chunks,List.of(),List.of());
        assertEquals("saved-vectors",degraded.get(0).retrievalMode());
        assertTrue(HybridRetriever.fallbackNotice(degraded).contains("检索降级"));
        doReturn(Map.of(0,1d)).when(store).semanticScores(anyLong(),anyList(),anyList());
        assertEquals("qdrant",retriever.search(1,"测试目标",chunks,List.of(),List.of()).get(0).retrievalMode());
    }
    @Test void keywordsCannotSelectUnrelatedEvidence() {
        var llm=mock(LlmGateway.class); var store=mock(VectorStore.class);
        when(llm.embed(anyString())).thenReturn(List.of(1d,0d));
        when(store.semanticScores(anyLong(),anyList(),anyList())).thenThrow(new IllegalStateException("network"));
        var retriever=new HybridRetriever(llm,new AgentProperties(),store);
        assertThrows(IllegalStateException.class,()->retriever.search(1,"完全无关问题",List.of(chunk(List.of())),List.of(),List.of()));
    }
    @Test void operatorsCanDisableFallback() {
        var llm=mock(LlmGateway.class); var store=mock(VectorStore.class);
        when(llm.embed(anyString())).thenReturn(List.of(1d,0d));
        when(store.semanticScores(anyLong(),anyList(),anyList())).thenThrow(new IllegalStateException("network"));
        assertThrows(IllegalStateException.class,()->new HybridRetriever(llm,new AgentProperties(),store,null,false)
            .search(1,"测试目标",List.of(chunk(List.of(1d,0d))),List.of(),List.of()));
        verify(store,times(2)).semanticScores(anyLong(),anyList(),anyList());
    }
    @Test void invalidDimensionsFailImmediatelyWithoutFallback() {
        var llm=mock(LlmGateway.class); var store=mock(VectorStore.class);
        when(llm.embed(anyString())).thenReturn(List.of(1d,0d));
        when(store.semanticScores(anyLong(),anyList(),anyList())).thenThrow(new IllegalArgumentException("dimension mismatch"));
        assertThrows(IllegalArgumentException.class,()->new HybridRetriever(llm,new AgentProperties(),store)
            .search(1,"测试目标",List.of(chunk(List.of(1d,0d))),List.of(),List.of()));
        verify(store,times(1)).semanticScores(anyLong(),anyList(),anyList());
    }
}
