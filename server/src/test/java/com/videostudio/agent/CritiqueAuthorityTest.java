package com.videostudio.agent;

import com.videostudio.config.AgentProperties;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.llm.LlmGateway;
import com.videostudio.retrieval.HybridRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验权责：确定性校验有否决权，模型 Critic 只有建议权。
 *
 * <p>实跑发现的真实问题：模型 Critic 会持续挑出「用词超出上下文」「推断性表述」
 * 这类措辞问题，每一轮都能找出新的，把正确报告永远卡在失败。这里钉住修复后的边界——
 * 模型再挑剔，只要证据能逐字核验，报告就必须能产出。
 */
class CritiqueAuthorityTest {

    private static final String TRANSCRIPT = "大家好，欢迎来到视频分析系统";

    private LlmGateway gateway(String criticVerdict) {
        var llm = mock(LlmGateway.class);
        when(llm.enabled()).thenReturn(true);
        when(llm.chat(anyList(), anyDouble(), anyInt(), anyString())).thenAnswer(call -> {
            String purpose = call.getArgument(3);
            String text = switch (purpose) {
                case "retrieval-plan" -> "{\"semanticQuery\":\"内容\",\"keywords\":[\"内容\"]}";
                case "plan" -> "{\"understoodGoal\":\"概括内容\",\"tasks\":[\"总结\"]}";
                case "execute" -> """
                        {"title":"测试报告",
                         "conclusions":["开场问候"],
                         "evidence":[{"timestampMs":1000,"source":"ASR",
                                      "content":"大家好","claim":"开场问候"}],
                         "suggestions":[],"sections":[]}
                        """;
                case "critic" -> criticVerdict;
                default -> "{}";
            };
            return new LlmGateway.ChatResult(text, 0, 0, "test");
        });
        return llm;
    }

    private AnalysisAgent agent(LlmGateway llm) {
        var retriever = mock(HybridRetriever.class);
        when(retriever.search(anyLong(), anyString(), anyList(), anyList(), anyList())).thenReturn(List.of());
        return new AnalysisAgent(llm, new PromptLibrary(), retriever, new AgentProperties());
    }

    private AnalysisAgent.Request request() {
        return new AnalysisAgent.Request(1, "概括内容", AnalysisMode.GENERAL,
                List.of(new TranscriptSegment(0, 10_000, TRANSCRIPT, null, null, "cloud-asr")),
                List.of(), List.of(), "0:10");
    }

    /** 模型说不通过，但证据逐字可核验：报告必须产出，意见保留 */
    @Test
    void modelCriticismDoesNotBlockAVerifiableReport() {
        String pickyCritic = """
                {"passed":false,
                 "feedback":["「开场问候」这个表述超出 VideoContext 的支持范围，属于推断"],
                 "missingRequirements":[],"unsupportedClaims":[],"requiredTimestamps":[]}
                """;

        AnalysisAgent.Outcome outcome = agent(gateway(pickyCritic)).run(request(), message -> { });

        assertEquals("测试报告", outcome.draft().title());
        assertTrue(outcome.critique().feedback().stream().anyMatch(f -> f.contains("超出")),
                "模型的意见要保留下来，供报告展示");
    }

    /** 证据在转写里找不到：确定性校验必须拦住，任务失败 */
    @Test
    void fabricatedEvidenceStillFailsTheTask() {
        var llm = gateway("{\"passed\":true,\"feedback\":[],\"missingRequirements\":[],"
                + "\"unsupportedClaims\":[],\"requiredTimestamps\":[]}");
        // 让 Executor 引一段原片里根本没有的话
        when(llm.chat(anyList(), anyDouble(), anyInt(), anyString())).thenAnswer(call -> {
            String purpose = call.getArgument(3);
            String text = switch (purpose) {
                case "retrieval-plan" -> "{\"semanticQuery\":\"内容\",\"keywords\":[\"内容\"]}";
                case "plan" -> "{\"understoodGoal\":\"概括内容\",\"tasks\":[\"总结\"]}";
                case "execute", "execute-retry" -> """
                        {"title":"编造的报告",
                         "conclusions":["会议决定明天上线"],
                         "evidence":[{"timestampMs":1000,"source":"ASR",
                                      "content":"会议决定明天上线","claim":"会议决定明天上线"}],
                         "suggestions":[],"sections":[]}
                        """;
                default -> "{\"passed\":true,\"feedback\":[],\"missingRequirements\":[],"
                        + "\"unsupportedClaims\":[],\"requiredTimestamps\":[]}";
            };
            return new LlmGateway.ChatResult(text, 0, 0, "test");
        });

        try {
            agent(llm).run(request(), message -> { });
            throw new AssertionError("编造的证据必须让任务失败");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("证据校验") || expected.getMessage().contains("未返回"),
                    "失败原因要能说明是证据问题：" + expected.getMessage());
        }
    }
}
