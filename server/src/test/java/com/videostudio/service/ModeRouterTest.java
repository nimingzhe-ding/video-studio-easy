package com.videostudio.service;
import com.videostudio.agent.PromptLibrary;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModeRouterTest {
    @Test void emptyGoalUsesExplicitGeneralDefaultWithoutCallingModel() {
        var llm=mock(LlmGateway.class);
        assertEquals(AnalysisMode.GENERAL,new ModeRouter(llm,new PromptLibrary()).route("").mode());
        verifyNoInteractions(llm);
    }
    @Test void outageDoesNotSilentlySelectAnotherMode() {
        var llm=mock(LlmGateway.class);
        when(llm.chat(anyList(),anyDouble(),anyInt(),anyString())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class,()->new ModeRouter(llm,new PromptLibrary()).route("学习笔记"));
    }
    @Test void invalidModelResponseFails() {
        var llm=mock(LlmGateway.class);
        when(llm.chat(anyList(),anyDouble(),anyInt(),anyString())).thenReturn(new LlmGateway.ChatResult("{\"mode\":\"UNKNOWN\"}",0,0,"test"));
        assertThrows(IllegalStateException.class,()->new ModeRouter(llm,new PromptLibrary()).route("学习笔记"));
    }
}
