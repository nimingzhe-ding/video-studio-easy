package com.videostudio.retrieval;

import com.videostudio.common.Texts;
import com.videostudio.config.AgentProperties;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.llm.LlmGateway;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 混合检索：语义相似度 + 词项命中率 + 画面文字命中率。
 * <p>权重与参考实现保持一致（0.60 / 0.25 / 0.15）：纯语义在专有名词、数字上容易漏，
 * 纯关键词又抓不住同义表达，混合后两者互补。
 */
@Component
public class HybridRetriever {

    private static final double W_SEMANTIC = 0.60;
    private static final double W_KEYWORD = 0.25;
    private static final double W_VISUAL = 0.15;

    private final LlmGateway llm;
    private final AgentProperties props;

    public HybridRetriever(LlmGateway llm, AgentProperties props) {
        this.llm = llm;
        this.props = props;
    }

    public record Hit(ContextChunk chunk, double score, List<TranscriptSegment> segments,
                      List<VisualEvidence> evidences) {
        public String window() {
            return chunk.window();
        }
    }

    /**
     * @param question 检索意图（模式说明 + 用户关注点）
     */
    public List<Hit> search(String question, List<ContextChunk> chunks,
                            List<TranscriptSegment> transcripts, List<VisualEvidence> visuals) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = Texts.keywords(question, 10);
        List<Double> queryVector = chunks.stream().anyMatch(c -> !c.embedding().isEmpty())
                ? llm.embed(question) : List.of();

        List<Hit> hits = new ArrayList<>();
        for (ContextChunk chunk : chunks) {
            double semantic = cosine(queryVector, chunk.embedding());
            double keyword = Texts.termScore(queryTerms, chunk.transcriptText() + " " + chunk.summary());
            double visual = Texts.termScore(queryTerms, chunk.ocrText());
            double score = W_SEMANTIC * semantic + W_KEYWORD * keyword + W_VISUAL * visual;
            hits.add(new Hit(chunk, score,
                    transcripts.stream().filter(s -> s.startMs() < chunk.endMs() && s.endMs() > chunk.startMs())
                            .sorted(Comparator.comparingLong(TranscriptSegment::startMs)).toList(),
                    visuals.stream().filter(v -> v.timeMs() >= chunk.startMs() && v.timeMs() < chunk.endMs())
                            .sorted(Comparator.comparingLong(VisualEvidence::timeMs)).toList()));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        int topK = Math.max(1, props.getTopKChunks());
        return new ArrayList<>(hits.subList(0, Math.min(topK, hits.size())));
    }

    /** 余弦相似度；维度不一致或为空时返回 0（避免异常打断检索） */
    public static double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || a.size() != b.size()) {
            return 0d;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0 || nb == 0) {
            return 0d;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
