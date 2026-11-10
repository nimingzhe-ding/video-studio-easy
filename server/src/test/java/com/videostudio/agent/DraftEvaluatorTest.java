package com.videostudio.agent;

import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物评测：分数必须可比较、可复现，且能把「好」和「差」区分开。
 *
 * <p>这些断言不是为了证明某个具体分数，而是锁定评分的相对关系——
 * 改提示词之后若高分突然低于低分样本，说明评测或产物出了问题。
 */
class DraftEvaluatorTest {

    private final DraftEvaluator evaluator = new DraftEvaluator();

    private final List<TranscriptSegment> transcripts = List.of(
            new TranscriptSegment(0, 60_000, "DeepSeek 昨晚发布了 Agent 新架构 Super Agent", null, null, "cloud-asr"),
            new TranscriptSegment(60_000, 120_000, "Router 层负责上下文维护与任务分发", null, null, "cloud-asr"));

    private final List<VisualEvidence> visuals = List.of(
            new VisualEvidence(17_000, "/tmp/a.jpg", "Deepseek 发布 Agent 新架构", null, "h", "ocr"));

    private AgentModels.Evidence evidence(long ts, String source, String content, String claim) {
        return new AgentModels.Evidence(ts, source, content, claim);
    }

    private AgentModels.Draft draft(String title, List<String> conclusions,
                                    List<AgentModels.Evidence> evidence) {
        return new AgentModels.Draft(title, conclusions, evidence, List.of(), List.of());
    }

    /** 证据能核验、结论有绑定：满分附近 */
    @Test
    void wellGroundedDraftScoresHigh() {
        AgentModels.Draft d = draft("架构摘要",
                List.of("发布了 Super Agent 新架构", "Router 负责分发"),
                List.of(evidence(1_000, "ASR", "DeepSeek 昨晚发布了 Agent 新架构 Super Agent", "发布了 Super Agent 新架构"),
                        evidence(70_000, "ASR", "Router 层负责上下文维护与任务分发", "Router 负责分发")));

        EvaluationReport report = evaluator.evaluate(d, transcripts, visuals, List.of());

        assertTrue(report.structuredValid());
        assertEquals(1.0, report.timestampCoverage(), 0.001);
        assertEquals(1.0, report.evidenceSupport(), 0.001);
        assertEquals(1.0, report.claimSupport(), 0.001);
        assertTrue(report.score() > 0.98, "证据完整的产物应该接近满分: " + report.score());
    }

    /** 证据全是编的：分数应当显著低，且给出说明 */
    @Test
    void fabricatedDraftScoresLow() {
        AgentModels.Draft d = draft("编造",
                List.of("股票明天涨停"),
                List.of(evidence(1_000, "ASR", "股票明天涨停且必须买入", "股票明天涨停")));

        EvaluationReport report = evaluator.evaluate(d, transcripts, visuals, List.of());

        assertEquals(0, report.evidenceSupport(), 0.001);
        assertEquals(0, report.claimSupport(), 0.001);
        assertTrue(report.score() < 0.3, "编造的产物不该拿到高分: " + report.score());
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("无法核验")));
    }

    /** 结构缺失直接 0 分，避免「没有证据所以没有失败项」的假高分 */
    @Test
    void emptyDraftScoresZero() {
        EvaluationReport report = evaluator.evaluate(draft("", List.of(), List.of()),
                transcripts, visuals, List.of());

        assertTrue(!report.structuredValid());
        assertEquals(0, report.score(), 0.001);
    }

    @Test
    void nullDraftIsHandled() {
        EvaluationReport report = evaluator.evaluate(null, transcripts, visuals, List.of());

        assertTrue(!report.structuredValid());
        assertEquals(0, report.score(), 0.001);
    }

    /** 期望关键词能反映「目标覆盖度」 */
    @Test
    void keywordCoverageReflectsGoalCoverage() {
        AgentModels.Draft d = draft("摘要",
                List.of("发布了 Super Agent 新架构"),
                List.of(evidence(1_000, "ASR", "DeepSeek 昨晚发布了 Agent 新架构 Super Agent", "发布了 Super Agent 新架构")));

        EvaluationReport covered = evaluator.evaluate(d, transcripts, visuals,
                List.of("Super Agent", "新架构"));
        EvaluationReport missing = evaluator.evaluate(d, transcripts, visuals,
                List.of("Super Agent", "Kubernetes"));

        assertEquals(1.0, covered.keywordCoverage(), 0.001);
        assertEquals(0.5, missing.keywordCoverage(), 0.001);
    }

    /** 同一个输入必须给出同一个分数，否则无法作为回归基线 */
    @Test
    void evaluationIsDeterministic() {
        AgentModels.Draft d = draft("摘要",
                List.of("发布了 Super Agent 新架构"),
                List.of(evidence(1_000, "ASR", "DeepSeek 昨晚发布了 Agent 新架构 Super Agent", "发布了 Super Agent 新架构")));

        double first = evaluator.evaluate(d, transcripts, visuals, List.of("Super Agent")).score();
        double second = evaluator.evaluate(d, transcripts, visuals, List.of("Super Agent")).score();

        assertEquals(first, second, 0.000001);
    }
}
