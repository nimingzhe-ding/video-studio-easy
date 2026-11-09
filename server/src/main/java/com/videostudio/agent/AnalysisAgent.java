package com.videostudio.agent;

import com.videostudio.common.Texts;
import com.videostudio.common.Times;
import com.videostudio.config.AgentProperties;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.infra.BudgetGuard;
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
    /** 可为空的成本护栏：单测里不需要它 */
    private final BudgetGuard budget;

    public AnalysisAgent(LlmGateway llm, PromptLibrary prompts, HybridRetriever retriever, AgentProperties props) {
        this(llm, prompts, retriever, props, null);
    }

    /**
     * 容器使用的构造器。
     * <p>类里有多个构造器时 Spring 不会自动挑，必须显式标注，否则启动直接报
     * 「No default constructor found」——这个错误直到真正打包启动才暴露，编译期查不出来。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisAgent(LlmGateway llm, PromptLibrary prompts, HybridRetriever retriever, AgentProperties props,
                         BudgetGuard budget) {
        this.llm = llm;
        this.prompts = prompts;
        this.retriever = retriever;
        this.props = props;
        this.budget = budget;
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

        if (!llm.enabled()) throw new IllegalStateException("模型服务未配置");

        // 三重上限：轮次、墙钟、token 预算。前两者防「跑太久」，后者防「花太多」——
        // 提示词写错导致循环调用时，只有 token 预算能真正止损。
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
            if (budget != null && budget.exceeded(request.jobId())) {
                throw new IllegalStateException("本任务模型预算已用尽");
            }
            rounds++;
            progress.accept("第 %d 轮：生成结构化产物".formatted(rounds));
            draft = execute(request, mode, plan, evidenceSegments, evidenceVisuals, draft, critique);
            if (draft == null || draft.empty()) {
                throw new IllegalStateException("模型未返回有效的结构化产物");
            }
            progress.accept("第 %d 轮：证据校验".formatted(rounds));
            AgentModels.Critique raw = critique(request, plan, draft, evidenceSegments, evidenceVisuals);
            // 模型的批评只作为「还值不值得改一轮」的信号，不决定任务成败。
            // 实跑经验：它每轮都能挑出新的措辞问题，把它当否决权会让正确报告永远出不来。
            critique = raw;
            if (raw.passed()) {
                break;
            }
            log.info("job {} 第 {} 轮模型提出建议：{}", request.jobId(), rounds, raw.feedback());
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
                progress.accept("第 %d 轮模型提出改进建议，补充 %d 条证据后重写".formatted(rounds, Math.max(0, added)));
            }
        }

        // 只有「没有产物」或「确定性校验始终不过」才判任务失败。
        // 循环里 raw.passed() 反映的是确定性校验结果——它为 false 时说明草稿里有
        // 编造的证据或不存在的时间戳，这种草稿绝不能发布；模型的措辞建议则不会走到这里。
        if (draft == null || draft.empty()) {
            throw new IllegalStateException("模型未返回有效的结构化产物");
        }
        if (critique == null || !critique.passed()) {
            throw new IllegalStateException("分析未通过证据校验或已超时，请调整目标后重试");
        }
        return new Outcome(draft, critique, plan, mode, "", rounds, "agent/" + llmName());
    }

    // ------------------------------------------------------------------ 步骤

    private AgentModels.RetrievalIntent planRetrieval(Request request, Consumer<String> progress) {
        if (!llm.enabled()) {
            throw new IllegalStateException("模型服务未配置");
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
        if (plan == null) throw new IllegalStateException("模型返回的计划结构不合法");
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
        // 必需小节由模式配置决定：GENERAL 没有 sectionKeys，就不要求模型输出 sections
        if (!mode.sectionKeys().isEmpty()) {
            user.append("\n本次分析模式的额外产物要求：").append(mode.guidance())
                    .append("\n在返回的 JSON 中额外包含 sections 数组，每个元素形如 ")
                    .append("{\"key\":\"英文标识\",\"title\":\"面向用户的标题\",\"items\":[\"要点\"]}；")
                    .append("可用的小节（key 需完全一致）：").append(String.join("、", mode.sectionKeys())).append('\n');
        }
        user.append("\nVideoContext:\n").append(context);

        // 2200 不够：10 分钟视频的产物包含十几条结论与证据，实测会被截断成半个 JSON，
        // 而截断后的内容无法解析，表现为「模型未返回有效产物」——一个纯配额问题伪装成模型故障。
        String raw = ask(prompts.get("system"), prompts.get("executor") + "\n\n" + user, 0.3, 4_096, "execute");
        AgentModels.Draft draft = ContextRenderer.parseDraft(raw);
        if (draft == null && previous == null) {
            logDraftFailure(request.jobId(), "首次", raw);
            draft = ContextRenderer.parseDraft(ask(prompts.get("system"),
                    prompts.get("executor") + "\n\n" + user + JSON_RETRY_SUFFIX, 0.1, 4_096, "execute-retry"));
            if (draft == null) {
                logDraftFailure(request.jobId(), "重试", raw);
            }
        }
        return draft;
    }

    /**
     * 产物解析失败时留下可诊断的线索。
     *
     * <p>「模型未返回有效产物」这个错误太笼统：它既可能是输出被 max_tokens 截断，
     * 也可能是模型写了散文而没有 JSON。前者要调配额，后者要调提示词，
     * 不给线索就只能靠猜。这里记录结尾片段与是否被截断。
     */
    private void logDraftFailure(long jobId, String which, String raw) {
        if (raw == null) {
            log.warn("job {} {}产物为空", jobId, which);
            return;
        }
        String tail = raw.length() <= 300 ? raw : "…" + raw.substring(raw.length() - 300);
        log.warn("job {} {}产物解析失败 len={} 疑似截断={} 尾部: {}",
                jobId, which, raw.length(), !raw.stripTrailing().endsWith("}"), tail);
    }

    /**
     * 一轮校验：模型 Critique + 确定性校验。
     *
     * <p>返回值的 {@code passed} 只反映<b>确定性校验</b>结果——模型的意见被保留在
     * feedback 里用于下一轮改进，但不构成否决。理由见
     * {@link EvidenceValidator#suggestions}。
     */
    private AgentModels.Critique critique(Request request, AgentModels.Plan plan, AgentModels.Draft draft,
                                          List<TranscriptSegment> segments, List<VisualEvidence> visuals) {
        String context = renderContext(request.goal(), segments, visuals, request.chunks());
        String user = prompts.get("critic") + "\n\nPlan:\n" + plan.render()
                + "\n\nDraft:\n" + com.videostudio.common.Json.writePretty(draft)
                + "\n\nVideoContext:\n" + context;
        String raw = ask(prompts.get("system"), user, 0.0, 900, "critic");
        AgentModels.Critique modelView = ContextRenderer.parseCritique(raw);
        if (modelView == null) {
            // Critic 只是建议角色，它自己的输出格式坏掉不该拖垮整个任务：
            // 长视频（3 分钟以上）上下文更大，模型返回非 JSON 的概率明显上升，
            // 而这时前段的转写与抽帧已经花掉了真实成本和时间。
            // 重试一次；仍失败就当作「没有额外建议」，由确定性校验独立裁决。
            log.warn("job {} 的 Critic 返回非结构化内容，重试一次", request.jobId());
            modelView = ContextRenderer.parseCritique(
                    ask(prompts.get("system"), user + JSON_RETRY_SUFFIX, 0.0, 900, "critic-retry"));
        }
        if (modelView == null) {
            log.warn("job {} 的 Critic 两次都无法解析，本轮跳过模型意见，只保留确定性校验", request.jobId());
            modelView = AgentModels.Critique.pass();
        }
        EvidenceValidator.Outcome hard = EvidenceValidator.verify(draft, segments, visuals,
                request.mode().sectionKeys());
        // 确定性校验是唯一有否决权的一路；模型意见只并入 feedback
        AgentModels.Critique merged = EvidenceValidator.merge(modelView, hard);
        return hard.passed() ? EvidenceValidator.suggestions(merged) : merged;
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
}
