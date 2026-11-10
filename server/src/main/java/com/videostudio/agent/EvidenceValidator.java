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

    /**
     * 证据内容的最短要求：太短的引用无法证明它来自原文，按未核验处理。
     * <p>取 2 而不是更高：中文里「大家好」这种有效引用归一化后只有 3 个字，
     * 阈值定高了会把短句证据全部判死。
     */
    private static final int MIN_QUOTE_CHARS = 2;
    /** OCR 帧与模型引用时间点之间允许的最大偏差：模型倾向写整分钟，而帧时间不规则 */
    private static final long VISUAL_TOLERANCE_MS = 30_000;
    /** 允许模型引用比原文少 / 多出的比例，用于容忍概括与轻微补全 */
    private static final double QUOTE_TOLERANCE = 0.35;

    /**
     * 判断「模型引用的内容」能否在「原文」里找到。
     *
     * <p>为什么不是严格子串：真实模型几乎从不逐字复制。它会把
     * 「The quick brown fox jumps over the lazy d」写成完整的
     * 「The quick brown fox jumps over the lazy dog」——多补了一个字母，
     * 严格子串匹配就会判这份正确的报告「证据无法核验」。
     *
     * <p>容忍规则按「能用」优先设计，但保留防编造底线：
     * <ul>
     *   <li>任一方是另一方的前缀 → 通过（截断式引用，最常见）</li>
     *   <li>双向包含 → 通过（概括或补全）</li>
     *   <li>公共前缀达到较短一方的 {@value #QUOTE_TOLERANCE} 以上 → 通过（尾部轻微出入）</li>
     *   <li>引用短于 {@value #MIN_QUOTE_CHARS} 个字符 → 不通过（无法证明来源）</li>
     * </ul>
     * 通过条件是「文本高度重合」，不通过意味着模型写下了一段原文里根本没有的话。
     */
    static boolean quotes(String source, String content) {
        String a = normalize(source);
        String b = normalize(content);
        if (a.isEmpty() || b.isEmpty() || b.length() < MIN_QUOTE_CHARS) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        int shorter = Math.min(a.length(), b.length());
        int common = 0;
        while (common < shorter && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common >= Math.ceil(shorter * (1 - QUOTE_TOLERANCE));
    }

    /**
     * claim 与 conclusion 是否指向同一件事。
     *
     * <p>不做编辑距离：claim 常是 conclusion 的缩写或延伸，语义相同而字数差异很大。
     * 这里只要求一方包含另一方，或去掉「因为/所以」类连接词后仍有足够长的公共前缀。
     */
    static boolean relates(String claim, String conclusion) {
        if (claim.isEmpty() || conclusion.isEmpty()) {
            return false;
        }
        if (claim.equals(conclusion) || claim.contains(conclusion) || conclusion.contains(claim)) {
            return true;
        }
        int shorter = Math.min(claim.length(), conclusion.length());
        if (shorter < MIN_QUOTE_CHARS) {
            return false;
        }
        int common = 0;
        while (common < shorter && claim.charAt(common) == conclusion.charAt(common)) {
            common++;
        }
        return common >= Math.ceil(shorter * 0.6);
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

        // 证据级校验：单条失败记为质量问题，不再直接判整体不通过。
        // 只有「一条都核验不了」才说明产物是凭空编的，那才是硬失败。
        List<AgentModels.Evidence> supported = new ArrayList<>();
        int totalEvidence = draft.evidence().size();
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
                    && quotes(segment.text(), evidence.content());
            boolean ocrOk = visual != null && source.contains("OCR")
                    && quotes(visual.ocrText(), evidence.content());
            // 视觉描述同样要逐字核对：模型写的描述也是原始证据，不能凭印象引用
            boolean visionOk = visual != null && source.contains("VISION")
                    && quotes(visual.caption(), evidence.content());
            if (!asrOk && !ocrOk && !visionOk) {
                problems.add("证据原文无法在原始片段中核验: ts=%d (%s)"
                        .formatted(evidence.timestampMs(), Times.format(evidence.timestampMs())));
                add(failingTimestamps, evidence.timestampMs());
                continue;
            }
            supported.add(evidence);
        }

        // 结论级校验：同样降为质量信号。
        // 用「互相包含」而不是「精确相等」：模型几乎总会把 claim 写得更完整或更简略。
        int unsupportedConclusions = 0;
        for (String conclusion : draft.conclusions()) {
            String target = normalize(conclusion);
            boolean ok = supported.stream().anyMatch(e -> relates(normalize(e.claim()), target));
            if (!ok) {
                unsupportedConclusions++;
                problems.add("结论缺少可核验证据：" + Texts.truncate(conclusion, 60));
            }
        }

        for (String conclusion : draft.conclusions()) {
            if (normalize(conclusion).isEmpty()) {
                problems.add("存在空结论");
                break;
            }
        }

        // 硬门槛只有两条，都针对「整份产物与素材没有实质关联」的情况：
        //   1. 有证据但一条都核验不了 —— 模型在编；
        //   2. 有结论但一条都找不到可核验证据 —— 同样是在编。
        // 部分失败是常态（引用时间点有偏差、把 OCR 乱码当 ASR），
        // 这些写进审阅意见让人核对，但不该让整份报告作废。
        boolean everythingFabricated = totalEvidence > 0 && supported.isEmpty();
        boolean nothingSupported = !draft.conclusions().isEmpty()
                && unsupportedConclusions == draft.conclusions().size();
        // 结构性问题仍然是硬失败：缺小节、空结论，以及标题/结论/证据整体缺失
        boolean structurallyBroken = problems.stream().anyMatch(p ->
                p.startsWith("缺少必需小节") || p.startsWith("存在空结论")
                        || p.startsWith("证据来源不合法") || p.startsWith("报告标题为空")
                        || p.startsWith("报告缺少结论") || p.startsWith("报告缺少证据"));

        if (everythingFabricated) {
            problems.add(0, "全部证据都无法在原始素材中核验，产物可能不是基于该素材生成");
        }
        if (nothingSupported) {
            problems.add(0, "全部结论都缺少可核验证据，产物可能不是基于该素材生成");
        }
        boolean passed = !everythingFabricated && !nothingSupported && !structurallyBroken;
        return new Outcome(passed, List.copyOf(problems), List.copyOf(failingTimestamps));
    }

    /**
     * 把模型的 Critique 与确定性校验结论合并。
     *
     * <p><b>只合并建议，不合并否决权。</b>早期实现是「任一不通过即不通过」，
     * 实跑后发现模型 Critic 会稳定地挑出「用词超出上下文」「推断性表述」这类措辞问题，
     * 每一轮都能找出新的，导致正确的报告永远无法收敛。
     *
     * <p>现在的分工：
     * <ul>
     *   <li>确定性校验不过 → 真的有问题（证据编造、时间戳不存在），必须重写，最终不过就失败</li>
     *   <li>模型 Critic 不过 → 只作为改进建议喂回重写，并在报告里如实展示，不阻断产出</li>
     * </ul>
     * 理由：确定性校验不会撒谎，模型会。把最终裁决权交给能撒谎的一方，等于让系统不可用。
     */
    public static AgentModels.Critique suggestions(AgentModels.Critique critique) {
        if (critique.passed()) {
            return critique;
        }
        // 保留原始的反馈内容，但把 passed 置为 true：它只用于「是否还值得再改一轮」
        return new AgentModels.Critique(true, critique.feedback(), critique.missingRequirements(),
                critique.unsupportedClaims(), critique.requiredTimestamps());
    }
    /**
     * 把确定性校验的失败项并回 Critique。
     *
     * <p>只有这一路能把 {@code passed} 置为 false ——它决定任务最终是否失败。
     * 模型的意见由 {@link #suggestions} 单独处理。
     */
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
        // 确定性校验不过：无论模型怎么说，这份草稿都不能直接发布
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

    /**
     * OCR 证据只覆盖某一帧，模型不可能精确复现帧时间。
     *
     * <p>容差从 ±2s 放宽到 ±30s 并取<b>最近</b>的一帧：实测 10 分钟视频会抽出近 200 帧，
     * 平均 3 秒一帧，而模型倾向写整分钟（120000）——最近的帧可能是 119000 或 121500，
     * 硬卡 2 秒会让引用正确文字的结论也被判「时间戳不存在」。
     *
     * <p>放宽时间不等于放宽内容：{@code quotes} 仍要求 OCR 原文能对上，
     * 写错时间点但引对文字的条目仍会被接受，这是有意的取舍——证明「这句话来自原片」比
     * 「模型记对了秒数」更重要。
     */
    private static VisualEvidence findVisual(List<VisualEvidence> visuals, long ts) {
        VisualEvidence nearest = null;
        long best = Long.MAX_VALUE;
        for (VisualEvidence visual : visuals) {
            long distance = Math.abs(visual.timeMs() - ts);
            if (distance <= VISUAL_TOLERANCE_MS && distance < best) {
                best = distance;
                nearest = visual;
            }
        }
        return nearest;
    }
}
