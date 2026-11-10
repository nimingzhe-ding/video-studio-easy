package com.videostudio.agent;

import com.videostudio.common.Texts;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 产物的确定性质量评测。
 *
 * <p>与 {@link EvidenceValidator} 的区别：校验器回答「能不能发布」（硬门槛），
 * 评测回答「比上一次好还是差」（可比较的分数）。同一批证据，
 * 校验器只给出通过与否，评测给出 0.82 与 0.75 这种能排序的数字。
 *
 * <p>因此这里<b>不设阈值、不做否决</b>：低分只是低分，要不要发布仍由校验器决定。
 * 把两者混在一起会让「调提示词」变成「猜阈值」。
 */
@Component
public class DraftEvaluator {

    public EvaluationReport evaluate(AgentModels.Draft draft, List<TranscriptSegment> transcripts,
                                     List<VisualEvidence> visuals, List<String> expectedKeywords) {
        List<String> notes = new ArrayList<>();
        if (draft == null) {
            notes.add("草稿为空");
            return new EvaluationReport(false, 0, 0, 0, 0, 0, 0, List.copyOf(notes));
        }
        boolean structured = !Texts.isBlank(draft.title())
                && !draft.conclusions().isEmpty() && !draft.evidence().isEmpty();
        if (!structured) {
            notes.add("结构不完整：缺少标题、结论或证据");
        }

        int evidenceCount = draft.evidence().size();
        int covered = 0;
        int supported = 0;
        for (AgentModels.Evidence evidence : draft.evidence()) {
            TranscriptSegment segment = segmentAt(transcripts, evidence.timestampMs());
            VisualEvidence visual = visualNear(visuals, evidence.timestampMs());
            if (segment != null || visual != null) {
                covered++;
            }
            if (matchesSource(evidence, segment, visual)) {
                supported++;
            }
        }
        // 没有证据时覆盖率记 0 而不是 1：空产物不该因为「没有失败项」而拿满分
        double coverage = evidenceCount == 0 ? 0 : (double) covered / evidenceCount;
        double support = evidenceCount == 0 ? 0 : (double) supported / evidenceCount;
        if (evidenceCount > 0 && supported == 0) {
            notes.add("全部证据都无法核验，产物可能与素材无关");
        } else if (evidenceCount > 0 && supported < evidenceCount) {
            notes.add("%d/%d 条证据无法核验".formatted(evidenceCount - supported, evidenceCount));
        }

        int conclusions = draft.conclusions().size();
        int claimSupported = 0;
        for (String conclusion : draft.conclusions()) {
            String target = EvidenceValidator.normalize(conclusion);
            // 必须绑定到「已核验通过」的证据，而不只是 claim 文本吻合：
            // 否则编造的产物只要 claim 抄对结论就能拿到满分绑定率，评测形同虚设
            boolean ok = draft.evidence().stream()
                    .filter(e -> EvidenceValidator.relates(EvidenceValidator.normalize(e.claim()), target))
                    .anyMatch(e -> matchesSource(e, segmentAt(transcripts, e.timestampMs()),
                            visualNear(visuals, e.timestampMs())));
            if (ok) {
                claimSupported++;
            }
        }
        double claims = conclusions == 0 ? 0 : (double) claimSupported / conclusions;

        double keywords = keywordCoverage(draft, expectedKeywords);
        if (expectedKeywords != null && !expectedKeywords.isEmpty() && keywords < 1) {
            notes.add("关键词覆盖 %d/%d".formatted(
                    Math.round(keywords * expectedKeywords.size()), expectedKeywords.size()));
        }
        return new EvaluationReport(structured, coverage, support, claims, keywords,
                evidenceCount, conclusions, List.copyOf(notes));
    }

    private double keywordCoverage(AgentModels.Draft draft, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return 1;
        }
        StringBuilder corpus = new StringBuilder(draft.title() == null ? "" : draft.title());
        draft.conclusions().forEach(corpus::append);
        draft.sections().forEach(section -> {
            corpus.append(section.title());
            section.items().forEach(corpus::append);
        });
        String haystack = EvidenceValidator.normalize(corpus.toString());
        long hit = expected.stream()
                .map(EvidenceValidator::normalize)
                .filter(keyword -> !keyword.isEmpty() && haystack.contains(keyword))
                .count();
        return (double) hit / expected.size();
    }

    private static TranscriptSegment segmentAt(List<TranscriptSegment> segments, long ts) {
        for (TranscriptSegment segment : segments) {
            if (segment.startMs() <= ts && ts < segment.endMs()) {
                return segment;
            }
        }
        return null;
    }

    private static VisualEvidence visualNear(List<VisualEvidence> visuals, long ts) {
        VisualEvidence nearest = null;
        long best = Long.MAX_VALUE;
        for (VisualEvidence visual : visuals) {
            long distance = Math.abs(visual.timeMs() - ts);
            if (distance <= 30_000 && distance < best) {
                best = distance;
                nearest = visual;
            }
        }
        return nearest;
    }

    private static boolean matchesSource(AgentModels.Evidence evidence,
                                         TranscriptSegment segment, VisualEvidence visual) {
        String source = evidence.source() == null ? "" : evidence.source().toUpperCase(Locale.ROOT);
        if (segment != null && source.contains("ASR")
                && EvidenceValidator.quotes(segment.text(), evidence.content())) {
            return true;
        }
        if (visual != null && source.contains("OCR")
                && EvidenceValidator.quotes(visual.ocrText(), evidence.content())) {
            return true;
        }
        return visual != null && source.contains("VISION")
                && EvidenceValidator.quotes(visual.caption(), evidence.content());
    }
}
