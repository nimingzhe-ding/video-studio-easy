package com.videostudio.agent;

import com.videostudio.common.Texts;
import com.videostudio.common.Times;
import com.videostudio.config.AgentProperties;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.llm.LlmGateway;
import com.videostudio.retrieval.HybridRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 受控 Agent 循环：路由 → 检索意图 → 规划 → 执行 → 校验（最多 N 轮）。
 * <p>与「让模型自由发挥」的区别在于：每一轮的产物都要过确定性证据校验，
 * 校验不过就把问题喂回去重写，而不是直接吐出一份无法核验的报告。
 */
@Component
public class AnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(AnalysisAgent.class);
    private static final String JSON_RETRY_SUFFIX = "\n请严格返回合法 JSON，不要添加解释或代码块。";

    private final LlmGateway llm;
    private final PromptLibrary prompts;
    private final HybridRetriever retriever;
    private final AgentProperties props;

    public AnalysisAgent(LlmGateway llm, PromptLibrary prompts, HybridRetriever retriever, AgentProperties props) {
        this.llm = llm;
        this.prompts = prompts;
        this.retriever = retriever;
        this.props = props;
    }

    public record Request(long jobId, String goal, AnalysisMode mode, List<TranscriptSegment> transcripts,
                          List<VisualEvidence> visuals, List<ContextChunk> chunks, String duration) {
    }

    public record Outcome(AgentModels.Draft draft, AgentModels.Critique critique, AgentModels.Plan plan,
                          AnalysisMode mode, String modeReason, int rounds, String engine) {
    }

    public Outcome run(Request request, Consumer<String> progress) {
        AgentModels.RetrievalIntent intent = planRetrieval(request, progress);
        AnalysisMode mode = request.mode();

        if (!llm.enabled()) {
            return offline(request, intent, mode, progress);
        }

        Instant deadline = Instant.now().plus(Duration.ofMillis(Math.max(30_000, props.getMaxDurationMs())));
        List<HybridRetriever.Hit> hits = retriever.search(request.jobId(), intent.query(), request.chunks(),
                request.transcripts(), request.visuals());
        List<TranscriptSegment> evidenceSegments = new ArrayList<>();
        List<VisualEvidence> evidenceVisuals = new ArrayList<>();
        for (HybridRetriever.Hit hit : hits) {
            evidenceSegments.addAll(hit.segments());
            evidenceVisuals.addAll(hit.evidences());
        }
        evidenceSegments = dedupe(evidenceSegments);
        evidenceVisuals = dedupeVisual(evidenceVisuals);
        progress.accept("检索到 %d 个片段、%d 条画面证据".formatted(evidenceSegments.size(), evidenceVisuals.size()));

        AgentModels.Plan plan = makePlan(request, mode, evidenceSegments, evidenceVisuals);
        progress.accept("计划已生成：" + plan.tasks().size() + " 个任务");

        AgentModels.Draft draft = null;
        AgentModels.Critique critique = null;
        int rounds = 0;
        int maxRounds = Math.max(1, props.getMaxRounds());
        while (rounds < maxRounds && Instant.now().isBefore(deadline)) {
            rounds++;
            progress.accept("第 %d 轮：生成结构化产物".formatted(rounds));
            draft = execute(request, mode, plan, evidenceSegments, evidenceVisuals, draft, critique);
            if (draft == null || draft.empty()) {
                progress.accept("模型未返回可用产物，切换到确定性模板");
                return offline(request, intent, mode, progress);
            }
            progress.accept("第 %d 轮：证据校验".formatted(rounds));
            critique = critique(request, plan, draft, evidenceSegments, evidenceVisuals);
            if (critique.passed()) {
                break;
            }
            log.info("job {} 第 {} 轮未通过校验：{}", request.jobId(), rounds, critique.feedback());
            if (rounds < maxRounds) {
                // 校验不过不能只是「把批评再喂回去重写一遍」——模型缺的是证据，不是措辞。
                // 这里按 Critic 指出的缺口去全量证据里回捞片段，并用缺口拼一次新的检索。
                int before = evidenceSegments.size() + evidenceVisuals.size();
                ContextRenderer.Retrieved recalled = recallByCritique(request, critique);
                evidenceSegments = expand(evidenceSegments, recalled.segments());
                evidenceVisuals = expandVisual(evidenceVisuals, recalled.visuals());
                List<HybridRetriever.Hit> extra = retriever.search(request.jobId(), critiqueQuery(request.goal(), critique),
                        request.chunks(), request.transcripts(), request.visuals());
                for (HybridRetriever.Hit hit : extra) {
                    evidenceSegments = expand(evidenceSegments, hit.segments());
                    evidenceVisuals = expandVisual(evidenceVisuals, hit.evidences());
                }
                int added = evidenceSegments.size() + evidenceVisuals.size() - before;
                log.info("job {} 第 {} 轮补充证据 {} 条（现有片段 {}、画面 {}）",
                        request.jobId(), rounds, added, evidenceSegments.size(), evidenceVisuals.size());
                progress.accept("第 %d 轮未通过校验，补充 %d 条证据后重写".formatted(rounds, Math.max(0, added)));
            }
        }

        if (draft == null) {
            return offline(request, intent, mode, progress);
        }
        if (critique == null) {
            critique = AgentModels.Critique.pass();
        }
        return new Outcome(draft, critique, plan, mode, "", rounds, "agent/" + llmName());
    }

    // ------------------------------------------------------------------ 步骤

    private AgentModels.RetrievalIntent planRetrieval(Request request, Consumer<String> progress) {
        if (!llm.enabled()) {
            return ContextRenderer.fallbackIntent(request.goal());
        }
        String user = prompts.get("retrieval-planner") + "\n\n用户目标：" + request.goal();
        String raw = ask(prompts.get("system"), user, 0.2, 400, "retrieval-plan");
        AgentModels.RetrievalIntent intent = ContextRenderer.parseIntent(raw, request.goal());
        progress.accept("检索意图：" + Texts.oneLine(intent.query(), 120));
        return intent;
    }

    private AgentModels.Plan makePlan(Request request, AnalysisMode mode,
                                      List<TranscriptSegment> segments, List<VisualEvidence> visuals) {
        String context = renderContext(request.goal(), segments, visuals, request.chunks());
        String instruction = "\n\n本次分析模式的额外拆解要求：" + mode.guidance();
        String user = prompts.get("planner") + instruction + "\n\nVideoContext:\n" + context;
        String raw = ask(prompts.get("system"), user, 0.2, 700, "plan");
        AgentModels.Plan plan = ContextRenderer.parsePlan(raw);
        if (plan == null) {
            String repair = user + "\n\n上一份计划不是合法结构，请补全目标理解并输出 1 到 5 个非空任务。" + JSON_RETRY_SUFFIX;
            plan = ContextRenderer.parsePlan(ask(prompts.get("system"), repair, 0.1, 700, "plan-repair"));
        }
        if (plan == null) {
            List<String> fallback = new ArrayList<>();
            fallback.add("梳理整体内容脉络与关键结论");
            fallback.addAll(mode.outlineHints());
            plan = new AgentModels.Plan("理解并总结：" + Texts.oneLine(request.goal(), 80),
                    fallback.subList(0, Math.min(5, fallback.size())));
        }
        return plan;
    }

    private AgentModels.Draft execute(Request request, AnalysisMode mode, AgentModels.Plan plan,
                                      List<TranscriptSegment> segments, List<VisualEvidence> visuals,
                                      AgentModels.Draft previous, AgentModels.Critique critique) {
        String context = renderContext(request.goal(), segments, visuals, request.chunks());
        StringBuilder user = new StringBuilder();
        user.append("Plan:\n").append(plan.render()).append('\n');
        if (critique != null && !critique.passed()) {
            user.append("PreviousCritique:\n").append(critique.render()).append('\n');
        }
        if (mode != AnalysisMode.GENERAL) {
            user.append("\n本次分析模式的额外产物要求：").append(mode.guidance())
                    .append("\n在返回的 JSON 中额外包含 sections 数组，每个元素形如 ")
                    .append("{\"key\":\"英文标识\",\"title\":\"面向用户的标题\",\"items\":[\"要点\"]}；")
                    .append("可用的小节（key 需完全一致）：").append(sectionKeys(mode)).append('\n');
        }
        user.append("\nVideoContext:\n").append(context);

        String raw = ask(prompts.get("system"), prompts.get("executor") + "\n\n" + user, 0.3, 2_200, "execute");
        AgentModels.Draft draft = ContextRenderer.parseDraft(raw);
        if (draft == null && previous == null) {
            draft = ContextRenderer.parseDraft(ask(prompts.get("system"),
                    prompts.get("executor") + "\n\n" + user + JSON_RETRY_SUFFIX, 0.1, 2_200, "execute-retry"));
        }
        return draft;
    }

    private AgentModels.Critique critique(Request request, AgentModels.Plan plan, AgentModels.Draft draft,
                                          List<TranscriptSegment> segments, List<VisualEvidence> visuals) {
        String context = renderContext(request.goal(), segments, visuals, request.chunks());
        String user = prompts.get("critic") + "\n\nPlan:\n" + plan.render()
                + "\n\nDraft:\n" + com.videostudio.common.Json.writePretty(draft)
                + "\n\nVideoContext:\n" + context;
        String raw = ask(prompts.get("system"), user, 0.0, 900, "critic");
        AgentModels.Critique critique = ContextRenderer.parseCritique(raw);
        if (critique == null) {
            critique = AgentModels.Critique.pass();
        }
        EvidenceValidator.Outcome outcome = EvidenceValidator.verify(draft, segments, visuals,
                sectionKeysList(request.mode()));
        return EvidenceValidator.merge(critique, outcome);
    }

    // ------------------------------------------------------------- 离线回退

    /**
     * 没有 API key 时的确定性产物：用分块摘要 + 关键句拼出报告。
     * <p>保证 demo 在完全离线的情况下也能上传、转写、抽帧、出文档。
     */
    private Outcome offline(Request request, AgentModels.RetrievalIntent intent, AnalysisMode mode,
                            Consumer<String> progress) {
        progress.accept("模型未配置，使用确定性模板生成报告");
        List<HybridRetriever.Hit> hits = retriever.search(request.jobId(), intent.query(), request.chunks(),
                request.transcripts(), request.visuals());
        List<TranscriptSegment> segments = new ArrayList<>();
        List<VisualEvidence> visuals = new ArrayList<>();
        for (HybridRetriever.Hit hit : hits) {
            segments.addAll(hit.segments());
            visuals.addAll(hit.evidences());
        }
        if (segments.isEmpty()) {
            segments.addAll(request.transcripts());
        }
        if (visuals.isEmpty()) {
            visuals.addAll(request.visuals());
        }
        segments = dedupe(segments);
        visuals = dedupeVisual(visuals);

        List<String> conclusions = new ArrayList<>();
        List<AgentModels.Evidence> evidence = new ArrayList<>();
        for (TranscriptSegment segment : segments.stream().limit(8).toList()) {
            String text = Texts.oneLine(segment.text(), 120);
            if (text.isBlank()) {
                continue;
            }
            conclusions.add(text);
            // 说话人写进 source：报告里的证据行会显示成「ASR（讲者 A）」，读者一眼能看到谁说的；
            // 校验器只做 source.contains("ASR")，加后缀不影响原有判定。
            String source = Texts.isBlank(segment.speaker()) ? "ASR" : "ASR（" + segment.speaker() + "）";
            evidence.add(new AgentModels.Evidence(segment.startMs(), source, text, text));
        }
        List<AgentModels.Section> sections = new ArrayList<>();
        List<String> hints = mode.outlineHints();
        for (int i = 0; i < hints.size(); i++) {
            // 离线模式也要把小节填满：没有模型时用确定性素材（画面文字 + 关键片段）充当内容，
            // 否则学习/评审/创作模式的报告只剩一个标题和空壳，看不出结构差异。
            sections.add(new AgentModels.Section(sectionKey(mode, hints.get(i)), hints.get(i),
                    offlineSectionItems(i, hints.get(i), segments, visuals)));
        }
        List<String> suggestions = List.of(
                "配置 AI_API_KEY 后可获得基于大模型的深度分析与更丰富的结构化小节。",
                "结合时间戳回到原片核对关键结论，尤其是数字、人名与结论性表述。");
        AgentModels.Draft draft = new AgentModels.Draft(
                "离线摘要：" + Texts.oneLine(request.goal(), 40), conclusions, evidence, suggestions, sections);
        AgentModels.Critique critique = AgentModels.Critique.pass();
        return new Outcome(draft, critique, new AgentModels.Plan("离线模式", List.of("抽取关键片段")),
                mode, "", 1, "offline-template");
    }

    /**
     * 离线模式的分节内容：不编造结论，只把已有证据按小节重新组织，
     * 让「学习笔记/评审纪要/创作素材」的结构在无模型时仍然成立。
     */
    private List<String> offlineSectionItems(int index, String hint,
                                             List<TranscriptSegment> segments, List<VisualEvidence> visuals) {
        List<String> items = new ArrayList<>();
        List<String> screenTexts = new ArrayList<>();
        for (VisualEvidence visual : visuals) {
            for (String line : Texts.normalizeWhitespace(visual.displayText()).split("\n")) {
                if (!line.isBlank()) {
                    screenTexts.add(Times.format(visual.timeMs()) + " " + Texts.oneLine(line, 60));
                }
            }
        }
        switch (index) {
            case 0 -> {
                if (!segments.isEmpty()) {
                    items.add(Texts.oneLine(segments.get(0).text(), 100));
                } else if (!screenTexts.isEmpty()) {
                    items.add(screenTexts.get(0));
                }
            }
            case 1 -> {
                if (hasScreenText(screenTexts)) {
                    items.addAll(screenTexts.subList(0, Math.min(6, screenTexts.size())));
                }
                for (TranscriptSegment segment : segments.stream().limit(4).toList()) {
                    items.add(Times.formatBracket(segment.startMs()) + " " + Texts.oneLine(segment.text(), 80));
                }
            }
            default -> {
                int from = Math.min(index, Math.max(0, segments.size() - 1));
                for (TranscriptSegment segment : segments.subList(from, Math.min(from + 2, segments.size()))) {
                    items.add(Times.formatBracket(segment.startMs()) + " " + Texts.oneLine(segment.text(), 90));
                }
                if (items.isEmpty() && !screenTexts.isEmpty()) {
                    items.add(screenTexts.get(Math.min(index, screenTexts.size() - 1)));
                }
            }
        }
        return items;
    }

    private static boolean hasScreenText(List<String> screenTexts) {
        return screenTexts != null && !screenTexts.isEmpty();
    }

    // ------------------------------------------------------------------ 工具

    private String renderContext(String goal, List<TranscriptSegment> segments, List<VisualEvidence> visuals,
                                 List<ContextChunk> chunks) {
        return ContextRenderer.render(goal, new ContextRenderer.Retrieved(chunks, segments, visuals), props);
    }

    /** 按 Critic 给出的缺口时间戳，从全量证据里回捞片段（而不是只看已选中的那几条） */
    private ContextRenderer.Retrieved recallByCritique(Request request, AgentModels.Critique critique) {
        long margin = 0;
        for (TranscriptSegment segment : request.transcripts()) {
            margin = Math.max(margin, segment.endMs() - segment.startMs());
        }
        return ContextRenderer.recall(critique.requiredTimestamps(), request.transcripts(), request.visuals(), margin);
    }

    /** 把「目标 + 反馈 + 缺失要求 + 无依据论断」拼成一次新的检索语句 */
    private static String critiqueQuery(String goal, AgentModels.Critique critique) {
        StringBuilder sb = new StringBuilder(goal == null ? "" : goal);
        if (!critique.feedback().isEmpty()) {
            sb.append(' ').append(String.join(" ", critique.feedback()));
        }
        if (!critique.missingRequirements().isEmpty()) {
            sb.append(' ').append(String.join(" ", critique.missingRequirements()));
        }
        if (!critique.unsupportedClaims().isEmpty()) {
            sb.append(' ').append(String.join(" ", critique.unsupportedClaims()));
        }
        return Texts.truncate(sb.toString().strip(), 400);
    }

    private static List<TranscriptSegment> expand(List<TranscriptSegment> base, List<TranscriptSegment> extra) {
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        List<TranscriptSegment> merged = new ArrayList<>(base);
        merged.addAll(extra);
        return dedupe(merged);
    }

    private static List<VisualEvidence> expandVisual(List<VisualEvidence> base, List<VisualEvidence> extra) {
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        List<VisualEvidence> merged = new ArrayList<>(base);
        merged.addAll(extra);
        return dedupeVisual(merged);
    }

    private String ask(String system, String user, double temperature, Integer maxTokens, String purpose) {
        LlmGateway.ChatResult result = llm.chat(List.of(
                LlmGateway.Message.system(system), LlmGateway.Message.user(user)), temperature, maxTokens, purpose);
        return result == null ? null : result.content();
    }

    private String llmName() {
        return "llm";
    }

    private static List<TranscriptSegment> dedupe(List<TranscriptSegment> segments) {
        List<TranscriptSegment> out = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (TranscriptSegment segment : segments) {
            if (!seen.contains(segment.startMs())) {
                seen.add(segment.startMs());
                out.add(segment);
            }
        }
        out.sort(java.util.Comparator.comparingLong(TranscriptSegment::startMs));
        return out;
    }

    private static List<VisualEvidence> dedupeVisual(List<VisualEvidence> visuals) {
        List<VisualEvidence> out = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (VisualEvidence visual : visuals) {
            if (!seen.contains(visual.timeMs())) {
                seen.add(visual.timeMs());
                out.add(visual);
            }
        }
        out.sort(java.util.Comparator.comparingLong(VisualEvidence::timeMs));
        return out;
    }

    static List<String> sectionKeysList(AnalysisMode mode) {
        return switch (mode) {
            case GENERAL -> List.of();
            case LEARNING -> List.of("outline", "keypoints", "quiz", "pitfalls");
            case REVIEW -> List.of("fallacies", "exaggerations", "omissions", "doubtful");
            case CREATION -> List.of("highlights", "titles", "intro", "script");
        };
    }

    private static String sectionKeys(AnalysisMode mode) {
        return String.join("、", sectionKeysList(mode));
    }

    private static String sectionKey(AnalysisMode mode, String hint) {
        List<String> keys = sectionKeysList(mode);
        int index = mode.outlineHints().indexOf(hint);
        return index >= 0 && index < keys.size() ? keys.get(index) : "section" + index;
    }
}
