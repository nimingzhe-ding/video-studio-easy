package com.videostudio.agent;

import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据校验器：画面描述（VISION）作为证据来源的行为。
 * <p>引入视觉描述的唯一风险是「模型自己编一句描述、再拿它当证据」，
 * 所以这里必须钉住三条：描述能核验、描述编造就失败、纯 OCR 场景不受影响。
 */
class EvidenceValidatorTest {

    private static VisualEvidence visual(long timeMs, String ocr, String caption) {
        return new VisualEvidence(timeMs, "/tmp/frame.jpg", ocr, caption, "0000000000000000", "vision-ocr");
    }

    private static TranscriptSegment segment(long startMs, long endMs, String text) {
        return new TranscriptSegment(startMs, endMs, text, null, null, "whisper");
    }

    private static AgentModels.Evidence evidence(long ts, String source, String content, String claim) {
        return new AgentModels.Evidence(ts, source, content, claim);
    }

    private static AgentModels.Draft draft(String conclusion, List<AgentModels.Evidence> evidence) {
        return new AgentModels.Draft("标题", List.of(conclusion), evidence, List.of(), List.of());
    }

    @Test
    void captionOnlyEvidencePassesVerification() {
        String caption = "一位讲师站在白板前讲解架构图";
        List<VisualEvidence> visuals = List.of(visual(30_000, "", caption));
        AgentModels.Draft d = draft("讲师在白板前讲解架构", List.of(evidence(30_000, "VISION", "讲师站在白板前", "讲师在白板前讲解架构")));

        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(d, List.of(), visuals, List.of());

        assertTrue(outcome.passed(), "视觉描述逐字可核对时应通过：" + outcome.problems());
        assertTrue(outcome.problems().isEmpty());
    }

    @Test
    void inventedCaptionIsRejected() {
        List<VisualEvidence> visuals = List.of(visual(30_000, "", "一位讲师站在白板前讲解架构图"));
        AgentModels.Draft d = draft("画面上有一位观众在鼓掌", List.of(evidence(30_000, "VISION", "观众在鼓掌", "画面上有一位观众在鼓掌")));

        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(d, List.of(), visuals, List.of());

        assertFalse(outcome.passed());
        assertTrue(outcome.problems().stream().anyMatch(p -> p.contains("无法在原始片段中核验")), outcome.problems().toString());
        assertEquals(List.of(30_000L), outcome.failingTimestamps());
    }

    @Test
    void captionIsNotAcceptedUnderOcrSource() {
        // 同一帧既有 OCR 也有描述时，标 OCR 就只能引用 OCR 的文字，不能拿描述凑数
        List<VisualEvidence> visuals = List.of(visual(30_000, "架构图", "讲师在讲解架构图"));
        AgentModels.Draft d = draft("讲师在讲解", List.of(evidence(30_000, "OCR", "讲师在讲解", "讲师在讲解")));

        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(d, List.of(), visuals, List.of());

        assertFalse(outcome.passed());
        assertTrue(outcome.problems().stream().anyMatch(p -> p.contains("无法在原始片段中核验")), outcome.problems().toString());
    }

    @Test
    void unknownSourceStillRejected() {
        List<VisualEvidence> visuals = List.of(visual(30_000, "架构图", null));
        AgentModels.Draft d = draft("讲了架构", List.of(evidence(30_000, "MODEL", "讲了架构", "讲了架构")));

        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(d, List.of(), visuals, List.of());

        assertFalse(outcome.passed());
        assertTrue(outcome.problems().stream().anyMatch(p -> p.contains("证据来源不合法")), outcome.problems().toString());
    }

    @Test
    void blankCaptionDoesNotRescueBlankOcr() {
        // 只有空白描述时不能把「没读到文字」的一帧变成证据
        List<VisualEvidence> visuals = List.of(visual(30_000, "", "   "));
        AgentModels.Draft d = draft("没有任何依据", List.of(evidence(30_000, "VISION", "空白", "没有任何依据")));

        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(d, List.of(), visuals, List.of());

        assertFalse(outcome.passed());
        assertTrue(outcome.problems().stream().anyMatch(p -> p.contains("无法在原始片段中核验")), outcome.problems().toString());
    }

    @Test
    void asrEvidenceStillWorks() {
        List<TranscriptSegment> segments = List.of(segment(0, 10_000, "大家好，欢迎来到 video studio easy"));
        AgentModels.Draft d = draft("开场问候", List.of(evidence(1_000, "ASR", "大家好", "开场问候")));

        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(d, segments, List.of(), List.of());

        assertTrue(outcome.passed(), outcome.problems().toString());
    }

    /**
     * 长视频的真实常态：部分证据对不上，但报告内容有价值。
     *
     * <p>实跑 10 分钟视频时，模型把 OCR 乱码当成 ASR 引用、时间点写偏几秒，
     * 逐条核验会全军覆没，但这些报告并非凭空编造。现在只有「一条都对不上」才判失败。
     */
    @Test
    void partialEvidenceFailureStillProducesAReport() {
        List<TranscriptSegment> segments = List.of(
                segment(0, 60_000, "DeepSeek 昨晚发布了 Agent 新架构 Super Agent"),
                segment(60_000, 120_000, "Router 层负责上下文维护与任务分发"));
        AgentModels.Draft d = new AgentModels.Draft("架构摘要",
                List.of("新架构叫 Super Agent", "Router 负责分发", "Worker 负责执行"),
                List.of(
                        evidence(1_000, "ASR", "DeepSeek 昨晚发布了 Agent 新架构 Super Agent", "新架构叫 Super Agent"),
                        // 时间点偏了 30 秒，且引用的是另一段的话——典型的模型误差
                        evidence(90_000, "ASR", "这段原文根本不存在于任何片段", "Worker 负责执行")),
                List.of(), List.of());

        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(d, segments, List.of(), List.of());

        assertTrue(outcome.passed(), "部分证据对不上不该让整份报告作废: " + outcome.problems());
        assertFalse(outcome.problems().isEmpty(), "失败项仍要记录下来供审阅");
    }

    /** 一条都核验不了，说明产物与素材无关，必须拦下 */
    @Test
    void allEvidenceFabricatedFailsTheTask() {
        List<TranscriptSegment> segments = List.of(segment(0, 60_000, "这是一段完全无关的转写内容"));
        AgentModels.Draft d = new AgentModels.Draft("编造摘要",
                List.of("股票明天会涨"),
                List.of(evidence(1_000, "ASR", "股票明天会涨且必须买入", "股票明天会涨")),
                List.of(), List.of());

        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(d, segments, List.of(), List.of());

        assertFalse(outcome.passed());
        assertTrue(outcome.problems().stream().anyMatch(p -> p.contains("全部证据都无法")),
                outcome.problems().toString());
    }

    /** 结构问题仍是硬失败：缺必需小节不能发布 */
    @Test
    void missingRequiredSectionIsStillHardFailure() {
        List<TranscriptSegment> segments = List.of(segment(0, 60_000, "讲解核心概念"));
        AgentModels.Draft d = new AgentModels.Draft("学习笔记",
                List.of("讲解核心概念"),
                List.of(evidence(1_000, "ASR", "讲解核心概念", "讲解核心概念")),
                List.of(), List.of());

        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(d, segments, List.of(),
                List.of("outline", "keypoints"));

        assertFalse(outcome.passed());
        assertTrue(outcome.problems().stream().anyMatch(p -> p.startsWith("缺少必需小节")));
    }

    /** OCR 帧时间不规则，模型写整分钟时应当匹配到最近的帧 */
    @Test
    void ocrEvidenceMatchesNearestFrameWithinTolerance() {
        List<VisualEvidence> visuals = List.of(visual(119_000, "架构图：Router 与 Worker", null));
        AgentModels.Draft d = new AgentModels.Draft("架构",
                List.of("架构包含 Router"),
                List.of(evidence(120_000, "OCR", "Router 与 Worker", "架构包含 Router")),
                List.of(), List.of());

        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(d, List.of(), visuals, List.of());

        assertTrue(outcome.passed(), outcome.problems().toString());
    }
}
