package com.videostudio.service;

import com.videostudio.agent.ContextRenderer;
import com.videostudio.agent.PromptLibrary;
import com.videostudio.common.Texts;
import com.videostudio.common.Times;
import com.videostudio.config.AgentProperties;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.AnalysisReport;
import com.videostudio.domain.ChatMessage;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.llm.LlmGateway;
import com.videostudio.repository.ChatRepository;
import com.videostudio.repository.ChunkRepository;
import com.videostudio.repository.JobRepository;
import com.videostudio.repository.ReportRepository;
import com.videostudio.repository.TranscriptRepository;
import com.videostudio.repository.VisualRepository;
import com.videostudio.retrieval.HybridRetriever;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于已生成报告与证据的追问。
 * <p>回答必须带 [mm:ss] 时间戳，时间戳来源与报告一致，都是可回查的转写/OCR 片段。
 */
@Service
public class FollowUpService {

    private static final int MAX_HISTORY = 8;

    private final JobRepository jobRepository;
    private final ReportRepository reportRepository;
    private final TranscriptRepository transcriptRepository;
    private final VisualRepository visualRepository;
    private final ChunkRepository chunkRepository;
    private final ChatRepository chatRepository;
    private final HybridRetriever retriever;
    private final LlmGateway llm;
    private final PromptLibrary prompts;
    private final AgentProperties agent;

    public FollowUpService(JobRepository jobRepository, ReportRepository reportRepository,
                           TranscriptRepository transcriptRepository, VisualRepository visualRepository,
                           ChunkRepository chunkRepository, ChatRepository chatRepository,
                           HybridRetriever retriever, LlmGateway llm, PromptLibrary prompts,
                           AgentProperties agent) {
        this.jobRepository = jobRepository;
        this.reportRepository = reportRepository;
        this.transcriptRepository = transcriptRepository;
        this.visualRepository = visualRepository;
        this.chunkRepository = chunkRepository;
        this.chatRepository = chatRepository;
        this.retriever = retriever;
        this.llm = llm;
        this.prompts = prompts;
        this.agent = agent;
    }

    /** 返回助手回答；同时把问答写入 chat_message，刷新页面可回看 */
    public String ask(long jobId, String question) {
        AnalysisJob job = jobRepository.find(jobId).orElse(null);
        if (job == null) {
            throw new com.videostudio.common.ApiException("任务不存在: " + jobId);
        }
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        // 先读历史再落库，避免当前问题在上下文里出现两次
        List<ChatMessage> history = tail(chatRepository.list(jobId, MAX_HISTORY));
        chatRepository.save(jobId, ChatMessage.USER, q, "[]");

        List<TranscriptSegment> segments = transcriptRepository.listByJob(jobId);
        List<VisualEvidence> visuals = visualRepository.listByJob(jobId);
        List<ContextChunk> chunks = chunkRepository.listByJob(jobId);
        String report = reportRepository.findByJob(jobId).map(AnalysisReport::markdown).orElse("");

        String answer;
        if (!llm.enabled() || chunks.isEmpty() && segments.isEmpty()) {
            answer = offlineAnswer(q, segments, visuals);
        } else {
            answer = cloudAnswer(job, q, history, report, segments, visuals, chunks);
            if (answer == null || answer.isBlank()) {
                answer = offlineAnswer(q, segments, visuals);
            }
        }
        String citations = com.videostudio.common.Json.write(Times.extractCitations(answer));
        chatRepository.save(jobId, ChatMessage.ASSISTANT, answer, citations);
        return answer;
    }

    public List<ChatMessage> history(long jobId) {
        return chatRepository.list(jobId, 200);
    }

    public void clear(long jobId) {
        chatRepository.clear(jobId);
    }

    private String cloudAnswer(AnalysisJob job, String question, List<ChatMessage> history, String report,
                               List<TranscriptSegment> segments, List<VisualEvidence> visuals,
                               List<ContextChunk> chunks) {
        List<HybridRetriever.Hit> hits = retriever.search(question, chunks, segments, visuals);
        List<TranscriptSegment> hitSegments = hits.stream()
                .flatMap(h -> h.segments().stream())
                .distinct()
                .sorted(java.util.Comparator.comparingLong(TranscriptSegment::startMs))
                .toList();
        List<VisualEvidence> hitVisuals = hits.stream()
                .flatMap(h -> h.evidences().stream())
                .distinct()
                .sorted(java.util.Comparator.comparingLong(VisualEvidence::timeMs))
                .toList();

        String context = ContextRenderer.render(question,
                new ContextRenderer.Retrieved(hits.stream().map(HybridRetriever.Hit::chunk).toList(),
                        hitSegments, hitVisuals), agent);

        List<LlmGateway.Message> messages = new ArrayList<>();
        messages.add(LlmGateway.Message.system(prompts.get("system")));
        messages.add(LlmGateway.Message.system(prompts.get("follow-up")));
        messages.add(LlmGateway.Message.system("本轮分析目标：" + (job.focus() == null ? "" : job.focus())
                + "\n媒体时长：" + Times.human(durationOf(segments, visuals))
                + "\n报告摘要：\n" + Texts.truncate(report, 3000)));
        for (ChatMessage past : history) {
            if (ChatMessage.USER.equals(past.role())) {
                messages.add(LlmGateway.Message.user(past.content()));
            } else if (ChatMessage.ASSISTANT.equals(past.role())) {
                messages.add(LlmGateway.Message.assistant(past.content()));
            }
        }
        messages.add(LlmGateway.Message.user(context + "\n\n用户追问：" + question));

        LlmGateway.ChatResult result = llm.chat(messages, 0.3, 900, "follow-up");
        return result == null ? null : result.content();
    }

    /** 离线兜底：直接从转写里找最相关的几段，明确标注「未接入大模型」 */
    private String offlineAnswer(String question, List<TranscriptSegment> segments, List<VisualEvidence> visuals) {
        List<String> tokens = new ArrayList<>(new LinkedHashSet<>(Texts.tokens(question)));
        List<TranscriptSegment> ranked = segments.stream()
                .sorted((a, b) -> Double.compare(
                        Texts.termScore(tokens, b.text()),
                        Texts.termScore(tokens, a.text())))
                .limit(3)
                .toList();
        List<VisualEvidence> rankedVisual = visuals.stream()
                .sorted((a, b) -> Double.compare(
                        Texts.termScore(tokens, b.ocrText()),
                        Texts.termScore(tokens, a.ocrText())))
                .limit(2)
                .filter(v -> Texts.termScore(tokens, v.ocrText()) > 0)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("> 当前未配置大模型（AI_API_KEY），以下是基于已有证据的检索结果，不是生成式回答。\n\n");
        if (ranked.isEmpty() && rankedVisual.isEmpty()) {
            sb.append("没有找到与「").append(Texts.truncate(question, 60)).append("」相关的片段。");
            return sb.toString();
        }
        for (TranscriptSegment s : ranked) {
            sb.append("- ").append(s.bracket()).append(' ')
                    .append(Texts.truncate(s.text(), 220)).append('\n');
        }
        for (VisualEvidence v : rankedVisual) {
            sb.append("- ").append(v.bracket()).append(" 画面文字：")
                    .append(Texts.truncate(v.ocrText(), 160)).append('\n');
        }
        return sb.toString().strip();
    }

    private long durationOf(List<TranscriptSegment> segments, List<VisualEvidence> visuals) {
        long end = 0;
        for (TranscriptSegment s : segments) {
            end = Math.max(end, s.endMs());
        }
        for (VisualEvidence v : visuals) {
            end = Math.max(end, v.timeMs());
        }
        return end;
    }

    private List<ChatMessage> tail(List<ChatMessage> all) {
        int from = Math.max(0, all.size() - MAX_HISTORY);
        return all.subList(from, all.size());
    }
}
