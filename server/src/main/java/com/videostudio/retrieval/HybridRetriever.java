package com.videostudio.retrieval;

import com.videostudio.common.Texts;
import com.videostudio.config.AgentProperties;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：语义相似度 + 词项命中率 + 画面文字命中率。
 * <p>权重与参考实现保持一致（0.60 / 0.25 / 0.15）：纯语义在专有名词、数字上容易漏，
 * 纯关键词又抓不住同义表达，混合后两者互补。
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private static final double W_SEMANTIC = 0.60;
    private static final double W_KEYWORD = 0.25;
    private static final double W_VISUAL = 0.15;

    private final LlmGateway llm;
    private final AgentProperties props;
    private final VectorStore vectors;
    private final com.videostudio.infra.JobMetrics metrics;
    private final boolean fallbackEnabled;

    public HybridRetriever(LlmGateway llm, AgentProperties props, VectorStore vectors) {
        this(llm,props,vectors,null,true);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public HybridRetriever(LlmGateway llm, AgentProperties props, VectorStore vectors,
        com.videostudio.infra.JobMetrics metrics,
        @org.springframework.beans.factory.annotation.Value("${retrieval.fallback-enabled:true}") boolean fallbackEnabled) {
        this.metrics=metrics; this.fallbackEnabled=fallbackEnabled;
        this.llm = llm;
        this.props = props;
        this.vectors = vectors;
    }

    public record Hit(ContextChunk chunk, double score, List<TranscriptSegment> segments,
                      List<VisualEvidence> evidences, String retrievalMode) {
        public Hit(ContextChunk chunk,double score,List<TranscriptSegment> segments,List<VisualEvidence> evidences) {
            this(chunk,score,segments,evidences,"qdrant");
        }
        public String window() {
            return chunk.window();
        }
    }

    /**
     * @param jobId    任务号，向量库按任务过滤
     * @param question 检索意图（模式说明 + 用户关注点）
     */
    public List<Hit> search(long jobId, String question, List<ContextChunk> chunks,
                            List<TranscriptSegment> transcripts, List<VisualEvidence> visuals) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = Texts.keywords(question, 10);
        List<Double> queryVector = llm.embed(question);
        if(queryVector.isEmpty() || queryVector.stream().anyMatch(v -> v==null || !Double.isFinite(v))
            || queryVector.stream().allMatch(v -> v==0d)) throw new IllegalStateException("查询向量无效");
        Semantic result=semanticScores(jobId,chunks,queryVector);
        Map<Integer,Double> semantic=result.scores();

        List<Hit> hits = new ArrayList<>();
        for (ContextChunk chunk : chunks) {
            double semanticScore = semantic.getOrDefault(chunk.chunkIndex(), 0d);
            double keyword = Texts.termScore(queryTerms, chunk.transcriptText() + " " + chunk.summary());
            double visual = Texts.termScore(queryTerms, chunk.ocrText());
            double score = W_SEMANTIC * semanticScore + W_KEYWORD * keyword + W_VISUAL * visual;
            hits.add(new Hit(chunk, score,
                    transcripts.stream().filter(s -> s.startMs() < chunk.endMs() && s.endMs() > chunk.startMs())
                            .sorted(Comparator.comparingLong(TranscriptSegment::startMs)).toList(),
                    visuals.stream().filter(v -> v.timeMs() >= chunk.startMs() && v.timeMs() < chunk.endMs())
                            .sorted(Comparator.comparingLong(VisualEvidence::timeMs)).toList(),result.mode()));
        }
        if("keywords".equals(result.mode())) {
            hits.removeIf(hit -> hit.score()<=0);
            if(hits.isEmpty()) throw new IllegalStateException("检索服务异常，关键词也未命中有效证据，请稍后重试");
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        int topK = Math.max(1, props.getTopKChunks());
        return new ArrayList<>(hits.subList(0, Math.min(topK, hits.size())));
    }

    private record Semantic(Map<Integer,Double> scores,String mode) {}
    private Semantic semanticScores(long jobId,List<ContextChunk> chunks,List<Double> query) {
        RuntimeException failure=null;
        for(int attempt=0;attempt<2;attempt++) {
            try {
                Map<Integer,Double> scores=vectors.semanticScores(jobId,chunks,query);
                if(scores.isEmpty()) throw new IllegalStateException("向量索引尚未就绪");
                return new Semantic(scores,"qdrant");
            } catch(IllegalArgumentException ex) {
                throw ex; // Invalid vectors/configuration must be corrected rather than hidden by fallback.
            } catch(RuntimeException ex) {
                failure=ex;
                if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("检索被中断",ex);
                if(attempt==0) {
                    try { Thread.sleep(100); }
                    catch(InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException("检索被中断",interrupted);
                    }
                }
            }
        }
        if(!fallbackEnabled) throw failure;
        Map<Integer,Double> local=new java.util.HashMap<>();
        for(var chunk:chunks) {
            if(chunk.embedding()!=null && chunk.embedding().size()==query.size()
                && !chunk.embedding().isEmpty() && chunk.embedding().stream().allMatch(v -> v!=null && Double.isFinite(v))
                && chunk.embedding().stream().anyMatch(v -> v!=0d)) {
                local.put(chunk.chunkIndex(),cosine(query,chunk.embedding()));
            }
        }
        boolean complete=local.size()==chunks.size();
        String mode=complete?"saved-vectors":"keywords";
        if(metrics!=null) metrics.retrievalFallback(mode);
        log.warn("Qdrant 重试后仍不可用 job={} mode={}",jobId,mode,failure);
        return new Semantic(complete?local:Map.of(),mode);
    }
    public static String fallbackNotice(List<Hit> hits) {
        if(hits.stream().anyMatch(hit -> "keywords".equals(hit.retrievalMode())))
            return "检索降级：向量服务暂不可用，本次使用关键词证据匹配；服务恢复后将优先使用完整检索。";
        if(hits.stream().anyMatch(hit -> "saved-vectors".equals(hit.retrievalMode())))
            return "检索降级：向量服务暂不可用，本次使用已保存的向量计算相似度；模型分析与证据校验照常执行。";
        return "";
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
