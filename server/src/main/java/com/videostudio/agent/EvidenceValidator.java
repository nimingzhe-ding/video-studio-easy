package com.videostudio.agent;

import com.videostudio.common.Texts;
import com.videostudio.common.Times;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 确定性证据校验：不信任模型的 self-report，逐条把结论回查到原始 ASR/OCR 文本。
 * <p>这是本项目的核心不变量——报告里的每个结论都必须能在原片时间轴上被机器复核。
 */
public final class EvidenceValidator {

    private EvidenceValidator() {
    }

    public record Outcome(boolean passed, List<String> problems, List<Long> failingTimestamps) {
    }

    /** 归一化：小写，去标点、符号与空白，消除模型的排版差异 */
    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    public static Outcome verify(AgentModels.Draft draft, List<TranscriptSegment> transcripts,
                                 List<VisualEvidence> visuals, List<String> requiredSectionKeys) {
        List<String> problems = new ArrayList<>();
        List<Long> failingTimestamps = new ArrayList<>();

        if (draft == null) return new Outcome(false,List.of("没有可校验的报告"),List.of());
        if (Texts.isBlank(draft.title())) problems.add("报告标题为空");
        if (draft.conclusions().isEmpty()) problems.add("报告缺少结论");
        if (draft.evidence().isEmpty()) problems.add("报告缺少证据");

        if (!requiredSectionKeys.isEmpty()) {
            List<String> present = draft.sections().stream().map(AgentModels.Section::key).toList();
            for (String key : requiredSectionKeys) {
                if (!present.contains(key)) {
                    problems.add("缺少必需小节：" + key);
                }
            }
        }

        // 证据级校验
        List<AgentModels.Evidence> supported = new ArrayList<>();
        for (AgentModels.Evidence evidence : draft.evidence()) {
            String source = evidence.source() == null ? "" : evidence.source().strip().toUpperCase(Locale.ROOT);
            if (!source.matches("(?:ASR|OCR|VISION)(?:\\+(?:ASR|OCR|VISION))*")) {
                problems.add("证据来源不合法（只能是 ASR / OCR / VISION）：" + source);
                add(failingTimestamps, evidence.timestampMs());
                continue;
            }
            if (normalize(evidence.content()).isEmpty() || normalize(evidence.claim()).isEmpty()) {
                problems.add("证据原文或支撑的结论为空");
                add(failingTimestamps,evidence.timestampMs());
                continue;
            }
            if (evidence.timestampMs()<0) {
                problems.add("证据时间戳不得为负数");
                continue;
            }
            TranscriptSegment segment = findSegment(transcripts, evidence.timestampMs());
            VisualEvidence visual = findVisual(visuals, evidence.timestampMs());
            if (segment == null && visual == null) {
                problems.add("证据时间戳不存在于原片：ts=" + evidence.timestampMs());
                add(failingTimestamps, evidence.timestampMs());
                continue;
            }
            boolean asrOk = segment != null && source.contains("ASR")
                    && normalize(segment.text()).contains(normalize(evidence.content()));
            boolean ocrOk = visual != null && source.contains("OCR")
                    && normalize(visual.ocrText()).contains(normalize(evidence.content()));
            // 视觉描述同样要逐字核对：模型写的描述也是原始证据，不能凭印象引用
            boolean visionOk = visual != null && source.contains("VISION")
                    && normalize(visual.caption()).contains(normalize(evidence.content()));
            if (!asrOk && !ocrOk && !visionOk) {
                problems.add("证据原文无法在原始片段中核验: ts=%d (%s)"
                        .formatted(evidence.timestampMs(), Times.format(evidence.timestampMs())));
                add(failingTimestamps, evidence.timestampMs());
                continue;
            }
            supported.add(evidence);
        }

        // 结论级校验：每条结论至少有一条 claim 与其一致、且已核验通过
        for (String conclusion : draft.conclusions()) {
            String target = normalize(conclusion);
            boolean ok = supported.stream().anyMatch(e -> normalize(e.claim()).equals(target));
            if (!ok) {
                problems.add("结论缺少可核验证据：" + Texts.truncate(conclusion, 60));
            }
        }

        if (!supported.isEmpty()) {
            for (String conclusion : draft.conclusions()) {
                if (normalize(conclusion).isEmpty()) {
                    problems.add("存在空结论");
                    break;
                }
            }
        }

        if (problems.isEmpty()) {
            return new Outcome(true, List.of(), List.of());
        }
        return new Outcome(false, List.copyOf(problems), List.copyOf(failingTimestamps));
    }

    /** 把确定性校验失败项并回模型的 Critique，形成下一轮的重写指令 */
    public static AgentModels.Critique merge(AgentModels.Critique critique, Outcome outcome) {
        if (outcome.passed()) {
            return critique;
        }
        List<String> feedback = new ArrayList<>(critique.feedback());
        feedback.addAll(outcome.problems());
        List<Long> timestamps = new ArrayList<>(critique.requiredTimestamps());
        for (Long ts : outcome.failingTimestamps()) {
            if (!timestamps.contains(ts)) {
                timestamps.add(ts);
            }
        }
        return new AgentModels.Critique(false, feedback, critique.missingRequirements(),
                critique.unsupportedClaims(), timestamps);
    }

    private static void add(List<Long> list, long value) {
        if (value >= 0 && !list.contains(value)) {
            list.add(value);
        }
    }

    private static TranscriptSegment findSegment(List<TranscriptSegment> segments, long ts) {
        for (TranscriptSegment segment : segments) {
            if (segment.startMs() <= ts && ts < segment.endMs()) {
                return segment;
            }
        }
        return null;
    }

    private static VisualEvidence findVisual(List<VisualEvidence> visuals, long ts) {
        for (VisualEvidence visual : visuals) {
            // OCR 只覆盖某一帧，允许 ±2s 的时间误差
            if (Math.abs(visual.timeMs() - ts) <= 2_000) {
                return visual;
            }
        }
        return null;
    }
}
