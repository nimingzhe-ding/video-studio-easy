package com.videostudio.asr;

import com.videostudio.common.Texts;
import com.videostudio.config.AppProperties;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.media.AudioExtractor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 转写编排：切片 → 并发逐片识别 → 映射回全局时间轴。
 *
 * <p>切片之间互不依赖，串行识别等于把整段耗时累加；一小时音频按 60s 切成 60 片，
 * 3 路并发能压到三分之一左右。并发度由 {@code app.media.asr-concurrency} 控制：
 * <ul>
 *   <li>云端接口：受账号 QPS 限制，通常 2-3 路合适；</li>
 * </ul>
 * 因此默认取 3，并允许按机器情况调整。
 *
 * <p>结果按切片顺序收集后再排序，保证时间轴单调，不受并发完成顺序影响。
 */
@Component
public class TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

    private final CloudSpeechRecognizer cloud;
    private final AudioExtractor audioExtractor;
    private final SpeakerDiarizer diarizer;
    private final AppProperties props;
    private final ExecutorService pool;

    public TranscriptionService(CloudSpeechRecognizer cloud,
                                AudioExtractor audioExtractor, SpeakerDiarizer diarizer, AppProperties props) {
        this.cloud = cloud;
        this.audioExtractor = audioExtractor;
        this.diarizer = diarizer;
        this.props = props;
        int threads = Math.max(1, props.getMedia().getAsrConcurrency());
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "asr-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    public record Outcome(List<TranscriptSegment> segments, String engine, List<String> warnings, int speakers) {
        public boolean empty() {
            return segments == null || segments.isEmpty();
        }

        /** 兼容不需要说话人数量的调用方 */
        public Outcome(List<TranscriptSegment> segments, String engine, List<String> warnings) {
            this(segments, engine, warnings, 0);
        }
    }

    public Outcome transcribe(Path wav, Path sliceDir, long durationMs, Consumer<String> progress) {
        int sliceSeconds = props.getMedia().getAsrSegmentSeconds();
        List<AudioExtractor.Slice> slices = audioExtractor.slice(wav, sliceDir, durationMs, sliceSeconds);
        if (slices.isEmpty()) {
            throw new IllegalStateException("音频切片失败");
        }

        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger();
        List<Future<SliceResult>> futures = new ArrayList<>(slices.size());
        for (AudioExtractor.Slice slice : slices) {
            futures.add(pool.submit(() -> {
                Optional<SpeechRecognizer.Result> result = recognize(slice.file(), warnings);
                progress.accept("语音识别 %d/%d".formatted(done.incrementAndGet(), slices.size()));
                return new SliceResult(slice, result.orElse(null));
            }));
        }

        // 按提交顺序取结果，保证输出顺序稳定；get() 会等待该片完成，但不影响前面的片已经并行跑完
        List<SliceResult> results = new ArrayList<>(slices.size());
        for (Future<SliceResult> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                futures.forEach(f -> f.cancel(true));
                Thread.currentThread().interrupt();
                throw new IllegalStateException("语音识别被中断",e);
            } catch (ExecutionException e) {
                futures.forEach(f -> f.cancel(true));
                // 把根因带进消息：只写「识别失败」会让用户分不清是配置错误、额度还是素材问题
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalStateException("语音片段识别失败："
                        + com.videostudio.common.Texts.oneLine(cause.getMessage(), 200), cause);
            }
        }

        List<TranscriptSegment> segments = new ArrayList<>();
        String engine = "none";
        for (SliceResult result : results) {
            if (result.recognized() == null || result.recognized().blank()) {
                continue;
            }
            SpeechRecognizer.Result r = result.recognized();
            engine = r.engine();
            AudioExtractor.Slice slice = result.slice();
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
        segments.sort(java.util.Comparator.comparingLong(TranscriptSegment::startMs));

        // 说话人分离必须在合并之前：合并会生成一个跨越几个片段的「长句」，
        // 之后就没法再把里面的换人切出来了。
        SpeakerDiarizer.Outcome diarized = diarizer.assign(wav, segments);
        segments = diarized.segments();
        if (diarized.split()) {
            progress.accept("说话人分离 %d 人".formatted(diarized.speakers()));
        }

        log.info("转写完成 engine={} slices={} concurrency={} segments={} speakers={} warnings={}",
                engine, slices.size(), props.getMedia().getAsrConcurrency(), segments.size(),
                diarized.speakers(), warnings.size());
        return new Outcome(merge(segments), engine, warnings, diarized.speakers());
    }

    private record SliceResult(AudioExtractor.Slice slice, SpeechRecognizer.Result recognized) {
    }

    private Optional<SpeechRecognizer.Result> recognize(Path file, List<String> warnings) {
        if (!cloud.available()) throw new IllegalStateException("云端语音识别尚未配置");
        RuntimeException last=null;
        for(int attempt=0;attempt<2;attempt++) {
            try { return Optional.of(cloud.transcribe(file)); }
            catch(RuntimeException ex) { last=ex; if(Thread.currentThread().isInterrupted()) throw ex; }
        }
        // 只抛出「请检查配置」等于把 401、额度耗尽、模型下线都糊成一句话，用户无从下手；
        // 把上游给出的原因（已脱敏）附在消息里，任务卡片才能直接显示可行动的信息。
        String detail = last == null ? "" : "：" + com.videostudio.common.Secrets.scrubOneLine(last.getMessage(), 200);
        throw new IllegalStateException("云端语音识别失败" + detail,last);
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
                // 不同说话人的两句话永远不合并：拼起来会让「谁说的」彻底丢失，
                // 而说话人恰恰是会议、访谈这类素材里最需要的信息
                boolean sameSpeaker = Objects.equals(last.speaker(), s.speaker());
                if (close && shortPiece && sameSpeaker) {
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
