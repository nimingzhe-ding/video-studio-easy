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
}
