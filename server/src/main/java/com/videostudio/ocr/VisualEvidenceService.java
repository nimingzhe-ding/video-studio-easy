package com.videostudio.ocr;

import com.videostudio.common.Texts;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.media.FrameDeduper;
import com.videostudio.media.FrameExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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

    public VisualEvidenceService(TextRecognizer recognizer, FrameDeduper deduper) {
        this.recognizer = recognizer;
        this.deduper = deduper;
    }

    public record Outcome(List<VisualEvidence> items, int scanned, int dropped) {
    }

    public Outcome extract(List<FrameExtractor.Frame> frames, Consumer<String> progress) {
        List<VisualEvidence> items = new ArrayList<>();
        FrameDeduper.Fingerprint previous = null;
        String previousText = null;
        int dropped = 0;
        int blank = 0;
        int scanned = 0;
        for (FrameExtractor.Frame frame : frames) {
            scanned++;
            if (scanned % 5 == 0) {
                progress.accept("画面文字识别 %d/%d".formatted(scanned, frames.size()));
            }
            FrameDeduper.Fingerprint fingerprint = deduper.fingerprint(frame.path());
            if (previous != null && fingerprint != null && fingerprint.dhash() == previous.dhash()
                    && FrameDeduper.signatureMad(previous.signature(), fingerprint.signature()) < SKIP_MAD) {
                dropped++;
                continue;
            }
            String text = recognizer.recognize(frame.path());
            String normalized = Texts.normalizeWhitespace(text);
            if (normalized.isEmpty()) {
                // 没有文字的画面（纯人像、纯风景）在本项目里不构成可核验证据，直接丢弃，避免污染检索。
                blank++;
                previous = fingerprint;
                previousText = null;
                continue;
            }
            if (normalized.equals(previousText)) {
                dropped++;
                continue;
            }
            previous = fingerprint;
            previousText = normalized;
            items.add(new VisualEvidence(frame.timeMs(), frame.path().toString(), text, null,
                    fingerprint == null ? null : fingerprint.hex(), recognizer.engine()));
        }
        log.info("画面证据完成 frames={} kept={} duplicate={} blank={}", frames.size(), items.size(), dropped, blank);
        return new Outcome(items, scanned, dropped);
    }
}
