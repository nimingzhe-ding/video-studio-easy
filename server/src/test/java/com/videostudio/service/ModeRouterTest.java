package com.videostudio.service;

import com.videostudio.agent.ModeRegistry;
import com.videostudio.agent.PromptLibrary;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模式路由：判定失败必须显式报错，可选模式清单必须来自注册表。
 */
class ModeRouterTest {

    private final ModeRegistry registry = new ModeRegistry("");

    private ModeRouter router(LlmGateway llm) {
        return new ModeRouter(llm, new PromptLibrary(), registry);
    }

    @Test
    void emptyGoalUsesExplicitGeneralDefaultWithoutCallingModel() {
        var llm = mock(LlmGateway.class);
        assertEquals("GENERAL", router(llm).route("").mode().code());
        verifyNoInteractions(llm);
    }

    @Test
    void outageDoesNotSilentlySelectAnotherMode() {
        var llm = mock(LlmGateway.class);
        when(llm.chat(anyList(), anyDouble(), anyInt(), anyString()))
                .thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> router(llm).route("学习笔记"));
    }

    @Test
    void invalidModelResponseFails() {
        var llm = mock(LlmGateway.class);
        when(llm.chat(anyList(), anyDouble(), anyInt(), anyString()))
                .thenReturn(new LlmGateway.ChatResult("{\"mode\":\"UNKNOWN\"}", 0, 0, "test"));
        assertThrows(IllegalStateException.class, () -> router(llm).route("学习笔记"));
    }

    /** 模型返回注册表里存在的模式：正常选中，并带上它的 label */
    @Test
    void registeredModeIsAccepted() {
        var llm = mock(LlmGateway.class);
        when(llm.chat(anyList(), anyDouble(), anyInt(), anyString()))
                .thenReturn(new LlmGateway.ChatResult("{\"mode\":\"REVIEW\",\"reason\":\"会议纪要\"}", 0, 0, "test"));

        ModeRouter.Decision decision = router(llm).route("整理会议纪要");

        assertEquals("REVIEW", decision.mode().code());
        assertEquals("评审纪要", decision.mode().label());
        assertTrue(decision.reason().contains("会议纪要"));
    }

    /** 可选模式清单要来自注册表，不能写死在提示词里——否则加模式时提示词会不同步 */
    @Test
    void promptListsModesFromTheRegistry() {
        var llm = mock(LlmGateway.class);
        when(llm.chat(anyList(), anyDouble(), anyInt(), anyString()))
                .thenReturn(new LlmGateway.ChatResult("{\"mode\":\"GENERAL\"}", 0, 0, "test"));

        router(llm).route("随便看看");

        org.mockito.ArgumentCaptor<java.util.List<LlmGateway.Message>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.Mockito.verify(llm).chat(captor.capture(), anyDouble(), anyInt(), anyString());
        String sent = captor.getValue().stream().map(LlmGateway.Message::content)
                .reduce("", (a, b) -> a + b);
        assertTrue(sent.contains("LEARNING"), "提示词里必须出现注册表中的模式标识");
        assertTrue(sent.contains("CREATION"), "提示词里必须出现注册表中的模式标识");
    }

    /** 模型把 JSON 包进 ``` 围栏是常见行为，不该因此判定失败 */
    @Test
    void fencedJsonIsStillParsed() {
        var llm = mock(LlmGateway.class);
        when(llm.chat(anyList(), anyDouble(), anyInt(), anyString())).thenReturn(new LlmGateway.ChatResult(
                "```json\n{\"mode\":\"LEARNING\",\"reason\":\"教学\"}\n```", 0, 0, "test"));

        assertEquals("LEARNING", router(llm).route("讲解知识点").mode().code());
    }

    /** 模式定义、必需小节与默认目标都来自配置，不在代码里分支 */
    @Test
    void modeCarriesItsOwnSectionKeysAndDefaultGoal() {
        AnalysisMode learning = registry.require("LEARNING");
        assertEquals(java.util.List.of("outline", "keypoints", "quiz", "pitfalls"), learning.sectionKeys());
        assertTrue(learning.defaultGoal().contains("学习笔记"));
        assertTrue(registry.require("GENERAL").sectionKeys().isEmpty(), "通用模式不该强制小节");
    }
}
