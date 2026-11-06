package com.videostudio.context;

import com.videostudio.common.Texts;
import com.videostudio.config.AgentProperties;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把转写与画面证据按固定窗口（默认 5 分钟）聚合成「上下文块」。
 * <p>块是检索与生成的基本单位：比句子大，能承载一整个话题；比整片小，能精确引用时间戳。
 */
public final class ChunkBuilder {

    private ChunkBuilder() {
    }

    public static List<ContextChunk> build(List<TranscriptSegment> transcripts, List<VisualEvidence> visuals,
                                           long durationMs, AgentProperties props) {
        long window = props.chunkMillis();
        long total = Math.max(durationMs, 1);
        List<ContextChunk> chunks = new ArrayList<>();
        int index = 0;
        for (long start = 0; start < total; start += window) {
            long from = start;
            long to = Math.min(start + window, total);
            List<TranscriptSegment> ts = transcripts.stream()
                    .filter(s -> overlaps(s.startMs(), s.endMs(), from, to))
                    .sorted(Comparator.comparingLong(TranscriptSegment::startMs))
                    .toList();
            List<VisualEvidence> vs = visuals.stream()
                    .filter(v -> v.timeMs() >= from && v.timeMs() < to)
                    .sorted(Comparator.comparingLong(VisualEvidence::timeMs))
                    .toList();
            if (ts.isEmpty() && vs.isEmpty()) {
                index++;
                continue;
            }
            String transcriptText = joinTranscript(ts);
            String ocrText = joinOcr(vs);
            List<String> keywords = Texts.keywords(transcriptText, 12);
            List<String> visualKeywords = Texts.keywords(ocrText, 10);
            String summary = summarize(transcriptText, ocrText, keywords);
            chunks.add(new ContextChunk(index, from, to, summary, keywords, visualKeywords,
                    transcriptText, ocrText, List.of(), 0d));
            index++;
        }
        return chunks;
    }

    private static boolean overlaps(long aStart, long aEnd, long bStart, long bEnd) {
        return aStart < bEnd && aEnd > bStart;
    }

    private static String joinTranscript(List<TranscriptSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (TranscriptSegment s : segments) {
            if (Texts.isBlank(s.text())) {
                continue;
            }
            sb.append(s.bracket()).append(' ').append(s.text().strip()).append('\n');
            if (sb.length() > 12_000) {
                sb.append("…（本段其余转写已裁剪）\n");
                break;
            }
        }
        return sb.toString().strip();
    }

    private static String joinOcr(List<VisualEvidence> visuals) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (VisualEvidence v : visuals) {
            // 优先用 OCR 文字；没有文字时退回视觉描述，让「纯画面」也能参与分块与检索
            boolean fromCaption = Texts.isBlank(v.ocrText());
            String text = fromCaption ? v.caption() : v.ocrText();
            if (Texts.isBlank(text)) {
                continue;
            }
            String normalized = Texts.oneLine(text, 400);
            if (!seen.add(normalized)) {
                continue;
            }
            sb.append(v.bracket()).append(' ').append(fromCaption ? "画面描述：" : "").append(normalized).append('\n');
            if (sb.length() > 6_000) {
                sb.append("…（本段其余画面文字已裁剪）\n");
                break;
            }
        }
        return sb.toString().strip();
    }

    /**
     * 确定性摘要：取首句 + 关键词。
     * <p>这是分块的初始值，随后由 {@link ChunkEnricher} 用模型改写；模型不可用时任务会失败，
     * 不会退回这份摘要作为最终产物。保留它是因为初始值决定了「模型改写失败时日志里能看到什么」。
     */
    private static String summarize(String transcript, String ocr, List<String> keywords) {
        List<String> parts = new ArrayList<>();
        if (!Texts.isBlank(transcript)) {
            String firstSentence = firstSentence(transcript);
            if (!firstSentence.isBlank()) {
                parts.add(firstSentence);
            }
        }
        if (!keywords.isEmpty()) {
            parts.add("关键词：" + String.join("、", keywords.subList(0, Math.min(6, keywords.size()))));
        }
        if (!Texts.isBlank(ocr) && parts.size() < 2) {
            parts.add("画面文字：" + Texts.oneLine(ocr, 120));
        }
        return String.join("；", parts);
    }

    private static String firstSentence(String text) {
        String cleaned = text.replaceAll("\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]", " ").replaceAll("\\s+", " ").strip();
        int cut = -1;
        for (char c : new char[]{'。', '！', '？', '.', '!', '?', '\n'}) {
            int idx = cleaned.indexOf(c);
            if (idx > 0 && (cut < 0 || idx < cut)) {
                cut = idx;
            }
        }
        String sentence = cut > 0 ? cleaned.substring(0, cut + 1) : cleaned;
        return Texts.oneLine(sentence, 160);
    }
}
