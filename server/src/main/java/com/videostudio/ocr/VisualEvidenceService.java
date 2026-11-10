package com.videostudio.ocr;

import com.videostudio.common.Texts;
import com.videostudio.config.AppProperties;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.llm.FrameCaptioner;
import com.videostudio.media.FrameDeduper;
import com.videostudio.media.FrameExtractor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 关键帧 → 去重 → OCR 的编排。
 *
 * <p>去重分两级，因为纯感知哈希在「整屏深色背景 + 中间一行标题」这类幻灯片上完全失效
 * （8x8 网格下四张不同标题的片子哈希一致）：
 * <ol>
 *   <li>快筛：与上一张保留帧的指纹几乎相同（dHash 相同且灰度签名平均差 &lt; 0.5）直接跳过，免去 OCR；</li>
 *   <li>文本判重：OCR 结果归一化后与上一张保留帧一致，判定为同一屏并丢弃，只保留第一次出现的时间点。</li>
 * </ol>
 * 第二级保证「换页但版式相似」的帧不会被误删——这正是长视频里最常见的课件/录屏形态。
 *
 * <p>三段式执行：<b>串行算指纹 → 并行 OCR → 串行文本判重</b>。
 * 指纹是纯内存的位运算（每帧几毫秒），OCR 是外部进程（每帧几百毫秒），
 * 所以把重的部分并行、轻的判重保持串行，既提速又不破坏「只和上一张保留帧比较」的语义。
 *
 * <p>可选第四步：OCR 完全没读到文字的帧（人像、实拍、图表），在用户打开视觉描述开关时
 * 交给视觉模型写一句描述，让这类画面也能成为可检索、可引用的证据。
 */
@Component
public class VisualEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(VisualEvidenceService.class);

    /**
     * 指纹快筛阈值：低于此值认为画面没有可见变化，连 OCR 都省掉。
     * <p>必须取得很小：深色课件上「换了一行标题」在 24x14 缩略图上平均差不到 0.1，
     * 阈值放到 0.5 会把整章的换页帧一起吞掉。
     */
    private static final double SKIP_MAD = 0.05;

    private final TextRecognizer recognizer;
    private final FrameDeduper deduper;
    private final FrameCaptioner captioner;
    private final ExecutorService pool;
    private final int concurrency;
    private final com.videostudio.infra.BudgetGuard budget;

    public VisualEvidenceService(TextRecognizer recognizer, FrameDeduper deduper, FrameCaptioner captioner,
                                 AppProperties props) { this(recognizer,deduper,captioner,props,null); }

    @org.springframework.beans.factory.annotation.Autowired
    public VisualEvidenceService(TextRecognizer recognizer, FrameDeduper deduper, FrameCaptioner captioner,
                                 AppProperties props, com.videostudio.infra.BudgetGuard budget) {
        this.budget=budget;
        this.recognizer = recognizer;
        this.deduper = deduper;
        this.captioner = captioner;
        int threads = Math.max(1, props.getMedia().getOcrConcurrency());
        this.concurrency = threads;
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "ocr-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * @param captioned 其中有多少条是靠视觉描述（而非 OCR 文字）保留下来的
     */
    public record Outcome(List<VisualEvidence> items, int scanned, int dropped, int captioned) {
    }

    /** 当前使用的画面文字识别引擎标识，供报告元信息展示。 */
    public String engine() {
        return recognizer.engine();
    }

    /** 视觉描述是否生效，供报告元信息展示。 */
    public boolean captioning() {
        return captioner.enabled();
    }

    /** 待 OCR 的帧 + 已经算好的指纹，避免重复计算 */
    private record Candidate(FrameExtractor.Frame frame, FrameDeduper.Fingerprint fingerprint) {
    }

    /** 一帧的最终结果：帧、指纹、OCR 文本 */
    private record Scanned(Candidate candidate, String text) {
    }

    public Outcome extract(List<FrameExtractor.Frame> frames, Consumer<String> progress) {
        // 第一段：串行算指纹并快筛。纯像素位运算，单帧毫秒级，不必并行。
        List<Candidate> candidates = new ArrayList<>();
        FrameDeduper.Fingerprint previous = null;
        int fastSkipped = 0;
        for (FrameExtractor.Frame frame : frames) {
            FrameDeduper.Fingerprint fingerprint = deduper.fingerprint(frame.path());
            if (previous != null && fingerprint != null && fingerprint.dhash() == previous.dhash()
                    && FrameDeduper.signatureMad(previous.signature(), fingerprint.signature()) < SKIP_MAD) {
                fastSkipped++;
                continue;
            }
            previous = fingerprint;
            candidates.add(new Candidate(frame, fingerprint));
        }

        // 第二段：并行 OCR。帧之间互不依赖，唯一共享的是外部进程与内存，由并发度配置控制。
        int total = candidates.size();
        AtomicInteger done = new AtomicInteger();
        List<Future<Scanned>> futures = new ArrayList<>(total);
        for (Candidate candidate : candidates) {
            futures.add(pool.submit(() -> {
                String text = recognizer.recognize(candidate.frame().path());
                progress.accept("画面文字识别 %d/%d".formatted(done.incrementAndGet(), total));
                return new Scanned(candidate, text);
            }));
        }
        List<Scanned> scannedFrames = new ArrayList<>(total);
        for (int i = 0; i < futures.size(); i++) {
            try {
                scannedFrames.add(futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException("画面识别被中断",e);
            } catch (ExecutionException e) {
                futures.forEach(f -> f.cancel(true));
                // 与语音识别一致：把「OCR 工具不可用」这类根因带进消息，否则用户只看到笼统的失败
                Throwable cause = e.getCause() == null ? e : e.getCause();
                String detail = cause.getMessage() == null ? ""
                        : "：" + com.videostudio.common.Secrets.scrubOneLine(cause.getMessage(), 200);
                throw new IllegalStateException("画面识别失败" + detail, cause);
            }
        }

        // 第三段：可选的视觉描述。只挑 OCR 什么都没读到的帧，且按时间顺序取前 N 帧，保证结果稳定。
        Map<Path, String> captions = captionBlankFrames(scannedFrames, progress);

        // 第四段：串行文本判重。必须按时间顺序看，才能定位「同一屏第一次出现的时刻」。
        List<VisualEvidence> items = new ArrayList<>();
        String previousText = null;
        int dropped = fastSkipped;
        int blank = 0;
        int captioned = 0;
        for (Scanned scanned : scannedFrames) {
            String normalized = Texts.normalizeWhitespace(scanned.text());
            FrameExtractor.Frame frame = scanned.candidate().frame();
            String caption = captions.get(frame.path());
            boolean captionOnly = normalized.isEmpty();
            String key = captionOnly ? Texts.normalizeWhitespace(caption) : normalized;
            if (key.isEmpty()) {
                // 既没有文字也没有描述的画面（纯人像、纯风景）不构成可核验证据，直接丢弃，避免污染检索。
                blank++;
                previousText = null;
                continue;
            }
            // 有描述就保留：这是唯一能让「没有文字的画面」进入证据链的途径。
            // 判重对文字与描述一视同仁——同一屏既可能重复出现同一段文字，也可能重复出现同一个画面。
            if (key.equals(previousText)) {
                dropped++;
                continue;
            }
            previousText = key;
            if (captionOnly) {
                captioned++;
                items.add(new VisualEvidence(frame.timeMs(), frame.path().toString(), "", caption,
                        hex(scanned.candidate()), recognizer.engine()));
            } else {
                items.add(new VisualEvidence(frame.timeMs(), frame.path().toString(), scanned.text(), caption,
                        hex(scanned.candidate()), recognizer.engine()));
            }
        }
        log.info("画面证据完成 frames={} candidates={} kept={} duplicate={} blank={} captioned={} concurrency={}",
                frames.size(), total, items.size(), dropped, blank, captioned, concurrency);
        return new Outcome(items, frames.size(), dropped, captioned);
    }

    /**
     * 为「OCR 没读到文字」的帧生成视觉描述。
     * <p>并发跑（网络请求几百毫秒到几秒），但只对前 quota 个空帧，且结果按帧号回填，顺序稳定。
     */
    private Map<Path, String> captionBlankFrames(List<Scanned> scannedFrames, Consumer<String> progress) {
        if (!captioner.enabled()) {
            return Map.of();
        }
        List<FrameExtractor.Frame> blanks = new ArrayList<>();
        for (Scanned scanned : scannedFrames) {
            if (Texts.normalizeWhitespace(scanned.text()).isEmpty()) {
                blanks.add(scanned.candidate().frame());
            }
        }
        int quota = Math.min(captioner.quota(), blanks.size());
        if (quota <= 0) {
            return Map.of();
        }
        List<FrameExtractor.Frame> targets = blanks.subList(0, quota);
        if (blanks.size() > quota) {
            log.info("待描述帧 {} 个，超过配额 {}，只描述最早的一批", blanks.size(), quota);
        }
        AtomicInteger count = new AtomicInteger();
        List<Future<Map.Entry<Path, String>>> futures = new ArrayList<>(targets.size());
        long jobId=budget==null?0:budget.currentJob();
        for (FrameExtractor.Frame frame : targets) {
            futures.add(pool.submit(() -> {
                if(budget!=null) budget.enter(jobId);
                try {
                    String caption = captioner.describe(frame.path());
                    progress.accept("画面描述 %d/%d".formatted(count.incrementAndGet(), targets.size()));
                    return Map.entry(frame.path(), caption);
                } finally { if(budget!=null) budget.leave(); }
            }));
        }
        Map<Path, String> captions = new HashMap<>();
        for (Future<Map.Entry<Path, String>> future : futures) {
            try {
                Map.Entry<Path, String> entry = future.get();
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    captions.put(entry.getKey(), entry.getValue());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException("画面描述被中断",e);
            } catch (ExecutionException e) {
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException("已启用的视觉描述服务调用失败",e.getCause());
            }
        }
        return captions;
    }

    private static String hex(Candidate candidate) {
        FrameDeduper.Fingerprint fingerprint = candidate.fingerprint();
        return fingerprint == null ? null : fingerprint.hex();
    }
}
