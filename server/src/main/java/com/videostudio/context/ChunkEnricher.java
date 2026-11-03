package com.videostudio.context;

import com.videostudio.common.Texts;
import com.videostudio.config.AgentProperties;
import com.videostudio.domain.ContextChunk;
import com.videostudio.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 上下文块增强：模型可用时用模型生成小结与关键词，不可用时保留确定性摘要。
 * <p>embedding 与摘要解耦：即使摘要调用失败，向量检索依然可用。
 */
@Component
public class ChunkEnricher {

    private static final Logger log = LoggerFactory.getLogger(ChunkEnricher.class);
    private static final int EMBED_BATCH = 8;

    private final LlmGateway llm;
    private final AgentProperties props;

    public ChunkEnricher(LlmGateway llm, AgentProperties props) {
        this.llm = llm;
        this.props = props;
    }

    public List<ContextChunk> enrich(List<ContextChunk> chunks, Consumer<String> progress) {
        if (chunks.isEmpty()) {
            return chunks;
        }
        List<ContextChunk> withEmbedding = attachEmbeddings(chunks, progress);
        if (!llm.enabled()) {
            return withEmbedding;
        }
        List<ContextChunk> summarized = new ArrayList<>();
        int done = 0;
        for (ContextChunk chunk : withEmbedding) {
            done++;
            progress.accept("生成分块摘要 %d/%d".formatted(done, withEmbedding.size()));
            String summary = summarize(chunk);
            summarized.add(summary == null || summary.isBlank() ? chunk : new ContextChunk(
                    chunk.chunkIndex(), chunk.startMs(), chunk.endMs(), summary.strip(),
                    chunk.keywords(), chunk.visualKeywords(), chunk.transcriptText(), chunk.ocrText(),
                    chunk.embedding(), 0d));
        }
        return summarized;
    }

    private String summarize(ContextChunk chunk) {
        String material = Texts.truncate(
                Texts.joinNonBlank("\n", "[语音转写]\n" + chunk.transcriptText(),
                        "[画面文字]\n" + chunk.ocrText()), 6_000);
        if (Texts.isBlank(material)) {
            return null;
        }
        String prompt = """
                下面是长视频中 %s 这一段的多模态素材（语音转写与画面 OCR）。
                请用中文写 2-3 句客观摘要：这段在讲什么、出现了哪些关键信息（人名/数字/结论）。
                不要臆测未出现的内容，不要加时间戳，不要加小标题。

                %s
                """.formatted(chunk.window(), material);
        LlmGateway.ChatResult result = llm.chat(List.of(
                LlmGateway.Message.system("你是严谨的视频内容摘要器，只依据给定素材写作，不做推测。"),
                LlmGateway.Message.user(prompt)), 0.2, 260, "chunk-summary");
        if (result == null) {
            return null;
        }
        return result.content().strip();
    }

    private List<ContextChunk> attachEmbeddings(List<ContextChunk> chunks, Consumer<String> progress) {
        if (!llm.enabled()) {
            return chunks;
        }
        List<String> texts = chunks.stream().map(ChunkEnricher::embeddingText).toList();
        List<List<Double>> vectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += EMBED_BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + EMBED_BATCH, texts.size()));
            progress.accept("建立向量索引 %d/%d".formatted(Math.min(i + EMBED_BATCH, texts.size()), texts.size()));
            List<List<Double>> part = llm.embed(batch);
            if (part.size() != batch.size()) {
                log.warn("embedding 返回数量不匹配，跳过向量索引（回退为词项检索）");
                return chunks;
            }
            vectors.addAll(part);
        }
        List<ContextChunk> out = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ContextChunk c = chunks.get(i);
            out.add(new ContextChunk(c.chunkIndex(), c.startMs(), c.endMs(), c.summary(), c.keywords(),
                    c.visualKeywords(), c.transcriptText(), c.ocrText(), vectors.get(i), 0d));
        }
        return out;
    }

    static String embeddingText(ContextChunk chunk) {
        return Texts.truncate(Texts.joinNonBlank("\n",
                "摘要：" + chunk.summary(),
                "关键词：" + String.join(" ", chunk.keywords()),
                "画面关键词：" + String.join(" ", chunk.visualKeywords()),
                "转写：" + chunk.transcriptText(),
                "画面文字：" + chunk.ocrText()), 4_000);
    }
}
