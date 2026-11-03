package com.videostudio.asr;

import com.videostudio.config.AppProperties;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.media.AudioExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 转写编排：切片 → 逐片识别 → 映射回全局时间轴。
 * <p>云端优先，失败自动回退本地 whisper；两者都不可用时返回空结果并提示降级，
 * 而不是让整个分析任务失败——没有转写仍然可以靠 OCR 阅读画面文字。
 */
@Component
public class TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

    private final CloudSpeechRecognizer cloud;
    private final WhisperCliRecognizer whisper;
    private final AudioExtractor audioExtractor;
    private final AppProperties props;

    public TranscriptionService(CloudSpeechRecognizer cloud, WhisperCliRecognizer whisper,
                                AudioExtractor audioExtractor, AppProperties props) {
        this.cloud = cloud;
        this.whisper = whisper;
        this.audioExtractor = audioExtractor;
        this.props = props;
    }

    public record Outcome(List<TranscriptSegment> segments, String engine, List<String> warnings) {
        public boolean empty() {
            return segments == null || segments.isEmpty();
        }
    }

    public Outcome transcribe(Path wav, Path sliceDir, long durationMs, Consumer<String> progress) {
        int sliceSeconds = props.getMedia().getAsrSegmentSeconds();
        List<AudioExtractor.Slice> slices = audioExtractor.slice(wav, sliceDir, durationMs, sliceSeconds);
        List<TranscriptSegment> segments = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (slices.isEmpty()) {
            warnings.add("未能切分出音频片段，跳过语音识别");
            return new Outcome(segments, "none", warnings);
        }
        String engine = "none";
        int done = 0;
        for (AudioExtractor.Slice slice : slices) {
            progress.accept("语音识别 %d/%d".formatted(done + 1, slices.size()));
            Optional<SpeechRecognizer.Result> result = recognize(slice.file(), warnings);
            if (result.isPresent() && !result.get().blank()) {
                SpeechRecognizer.Result r = result.get();
                engine = r.engine();
                if (r.words().isEmpty()) {
                    segments.add(new TranscriptSegment(slice.startMs(), slice.endMs(), r.text(), null, null, r.engine()));
                } else {
                    for (SpeechRecognizer.Word word : r.words()) {
                        long start = slice.startMs() + word.startMs();
                        long end = Math.min(slice.endMs(), slice.startMs() + Math.max(word.endMs(), word.startMs() + 200));
                        segments.add(new TranscriptSegment(start, end, word.text(), null, null, r.engine()));
                    }
                }
            }
            done++;
        }
        segments.sort(java.util.Comparator.comparingLong(TranscriptSegment::startMs));
        log.info("转写完成 engine={} segments={} warnings={}", engine, segments.size(), warnings.size());
        return new Outcome(merge(segments), engine, warnings);
    }

    private Optional<SpeechRecognizer.Result> recognize(Path file, List<String> warnings) {
        if (cloud.available()) {
            for (int attempt = 1; attempt <= Math.max(1, 2); attempt++) {
                try {
                    return Optional.of(cloud.transcribe(file));
                } catch (RuntimeException e) {
                    log.warn("云端 ASR 第 {} 次失败 {}: {}", attempt, file.getFileName(), e.getMessage());
                    if (attempt == 2) {
                        warnings.add("云端语音识别失败，已回退本地 whisper: " + com.videostudio.common.Texts.oneLine(e.getMessage(), 120));
                    }
                }
            }
        }
        if (whisper.available()) {
            try {
                return Optional.of(whisper.transcribe(file));
            } catch (RuntimeException e) {
                log.warn("本地 whisper 失败 {}: {}", file.getFileName(), e.getMessage());
                warnings.add("本地语音识别失败: " + com.videostudio.common.Texts.oneLine(e.getMessage(), 120));
            }
        } else if (!cloud.available()) {
            warnings.add("未配置任何语音识别引擎（云端或本地 whisper），本次仅基于画面文字分析");
        }
        return Optional.empty();
    }

    /** 合并过碎的片段：同一说话人的连续短句拼成一句，减少后续上下文里的噪声 */
    private List<TranscriptSegment> merge(List<TranscriptSegment> segments) {
        List<TranscriptSegment> merged = new ArrayList<>();
        for (TranscriptSegment s : segments) {
            if (s.text() == null || s.text().isBlank()) {
                continue;
            }
            if (!merged.isEmpty()) {
                TranscriptSegment last = merged.get(merged.size() - 1);
                boolean close = s.startMs() - last.endMs() < 800;
                boolean shortPiece = last.text().length() < 80;
                if (close && shortPiece) {
                    merged.set(merged.size() - 1, new TranscriptSegment(last.startMs(), Math.max(last.endMs(), s.endMs()),
                            (last.text() + " " + s.text()).strip(), last.speaker(), last.confidence(), last.source()));
                    continue;
                }
            }
            merged.add(s);
        }
        return merged;
    }
}
