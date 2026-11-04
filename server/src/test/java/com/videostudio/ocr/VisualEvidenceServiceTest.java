package com.videostudio.ocr;

import com.videostudio.config.AiProperties;
import com.videostudio.config.AppProperties;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.llm.FrameCaptioner;
import com.videostudio.llm.LlmGateway;
import com.videostudio.media.FrameDeduper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画面证据服务：视觉描述（caption）与两级去重的关系。
 *
 * <p>关键不变量：<b>只有「OCR 什么都没读到」的帧才配用视觉描述顶上</b>。
 * 配了描述就写描述，没配描述的空帧照旧丢弃——否则纯人像画面会以空文本进入检索，
 * 把 top-k 的预算浪费在没有任何内容可引用的条目上。
 *
 * <p>因为不能真的调模型，这里用 {@link AiProperties} 的 mock 模式让 {@link FrameCaptioner}
 * 恒返回空串，用子类覆写的方式注入假描述。
 */
class VisualEvidenceServiceTest {

    /** 固定返回一张纯色图：保证指纹稳定、能命中「上一帧相同」的快筛 */
    private static Path blankImage(Path dir, String name, Color color) throws IOException {
        BufferedImage img = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 64, 36);
        g.dispose();
        Path file = dir.resolve(name);
        ImageIO.write(img, "jpg", file.toFile());
        return file;
    }

    private static AppProperties props() {
        AppProperties props = new AppProperties();
        props.getMedia().setOcrConcurrency(2);
        return props;
    }

    /** 按图片文件名决定「识别结果」——模拟 OCR 读到 / 读不到的情况 */
    private static TextRecognizer recognizer(Map<String, String> table) {
        return new TextRecognizer() {
            @Override
            public String recognize(Path image) {
                return table.getOrDefault(image.getFileName().toString(), "");
            }

            @Override
            public String engine() {
                return "vision-ocr";
            }
        };
    }

    /** 假描述器：不联网，按配额返回固定文本 */
    private static FrameCaptioner captioner(Map<Path, String> table, int quota) {
        AiProperties ai = new AiProperties();
        ai.setApiKey("test-key");
        ai.setVisionCaptionEnabled(true);
        ai.setVisionCaptionMaxFrames(quota);
        FrameCaptioner real = new FrameCaptioner(new LlmGateway(ai), ai);
        return new FrameCaptioner(new LlmGateway(ai), ai) {
            @Override
            public String describe(Path frame) {
                return table.getOrDefault(frame, "");
            }

            @Override
            public boolean enabled() {
                return real.enabled();
            }

            @Override
            public int quota() {
                return real.quota();
            }
        };
    }

    @Test
    void blankFrameWithCaptionBecomesEvidence() throws IOException {
        Path dir = Files.createTempDirectory("vse-caption");
        Path frame = blankImage(dir, "a.jpg", new Color(20, 30, 40));
        Map<Path, String> captions = new ConcurrentHashMap<>();
        captions.put(frame, "一位讲师站在白板前讲解架构图");

        VisualEvidenceService service = new VisualEvidenceService(
                recognizer(Map.of()), new FrameDeduper(), captioner(captions, 24), props());
        VisualEvidenceService.Outcome outcome = service.extract(
                List.of(new com.videostudio.media.FrameExtractor.Frame(frame, 30_000, 0)), p -> { });

        assertEquals(1, outcome.items().size());
        assertEquals(1, outcome.captioned());
        VisualEvidence item = outcome.items().get(0);
        assertEquals("", item.ocrText(), "空帧的 ocrText 应该保持空串，描述单独存在 caption 里");
        assertEquals("一位讲师站在白板前讲解架构图", item.caption());
        assertTrue(item.captionOnly());
        assertEquals("一位讲师站在白板前讲解架构图", item.displayText());
    }

    @Test
    void blankFrameWithoutCaptionIsStillDropped() throws IOException {
        Path dir = Files.createTempDirectory("vse-blank");
        Path frame = blankImage(dir, "a.jpg", new Color(10, 10, 10));

        // 开关关掉（默认）：不产生描述，空帧照旧丢弃
        AiProperties ai = new AiProperties();
        FrameCaptioner off = new FrameCaptioner(new LlmGateway(ai), ai);
        VisualEvidenceService service = new VisualEvidenceService(
                recognizer(Map.of()), new FrameDeduper(), off, props());
        VisualEvidenceService.Outcome outcome = service.extract(
                List.of(new com.videostudio.media.FrameExtractor.Frame(frame, 0, 0)), p -> { });

        assertTrue(outcome.items().isEmpty());
        assertEquals(0, outcome.captioned());
    }

    @Test
    void captionRespectsQuota() throws IOException {
        Path dir = Files.createTempDirectory("vse-quota");
        List<com.videostudio.media.FrameExtractor.Frame> frames = new ArrayList<>();
        Map<Path, String> captions = new ConcurrentHashMap<>();
        // 四张内容不同的空帧，配额只有 2
        for (int i = 0; i < 4; i++) {
            Path frame = blankImage(dir, "f%d.jpg".formatted(i), new Color(10 + i * 40, 20, 30));
            frames.add(new com.videostudio.media.FrameExtractor.Frame(frame, i * 10_000L, i));
            captions.put(frame, "描述 " + i);
        }
        AtomicInteger calls = new AtomicInteger();
        AiProperties ai = new AiProperties();
        ai.setApiKey("test-key");
        ai.setVisionCaptionEnabled(true);
        ai.setVisionCaptionMaxFrames(2);
        FrameCaptioner counting = new FrameCaptioner(new LlmGateway(ai), ai) {
            @Override
            public String describe(Path frame) {
                calls.incrementAndGet();
                return captions.getOrDefault(frame, "");
            }
        };

        VisualEvidenceService service = new VisualEvidenceService(
                recognizer(Map.of()), new FrameDeduper(), counting, props());
        VisualEvidenceService.Outcome outcome = service.extract(frames, p -> { });

        assertEquals(2, calls.get(), "配额 2 就只能调 2 次视觉模型");
        assertEquals(2, outcome.captioned());
    }

    @Test
    void captionFillsInForFrameWithTextButKeepsOcrText() throws IOException {
        Path dir = Files.createTempDirectory("vse-both");
        Path frame = blankImage(dir, "a.jpg", new Color(7, 8, 9));
        Map<Path, String> captions = new ConcurrentHashMap<>();
        captions.put(frame, "一整页的架构示意图");

        VisualEvidenceService service = new VisualEvidenceService(
                recognizer(Map.of("a.jpg", "第二章 关键设计")), new FrameDeduper(),
                captioner(captions, 24), props());
        VisualEvidenceService.Outcome outcome = service.extract(
                List.of(new com.videostudio.media.FrameExtractor.Frame(frame, 18_000, 0)), p -> { });

        assertEquals(1, outcome.items().size());
        VisualEvidence item = outcome.items().get(0);
        assertEquals("第二章 关键设计", item.ocrText());
        assertFalse(item.captionOnly(), "有 OCR 文字时不该被标记成「只有描述」");
        assertEquals("第二章 关键设计", item.displayText());
    }

    @Test
    void duplicateFramesStillDeduplicated() throws IOException {
        Path dir = Files.createTempDirectory("vse-dup");
        Path a = blankImage(dir, "a.jpg", new Color(30, 30, 30));
        Path b = blankImage(dir, "b.jpg", new Color(30, 30, 30));

        VisualEvidenceService service = new VisualEvidenceService(
                recognizer(Map.of("a.jpg", "同一页", "b.jpg", "同一页")), new FrameDeduper(),
                captioner(Map.of(), 0), props());
        VisualEvidenceService.Outcome outcome = service.extract(List.of(
                new com.videostudio.media.FrameExtractor.Frame(a, 0, 0),
                new com.videostudio.media.FrameExtractor.Frame(b, 5_000, 1)), p -> { });

        assertEquals(1, outcome.items().size(), "同一屏只保留第一次出现的时刻");
        assertTrue(outcome.dropped() >= 1);
    }

    @Test
    void duplicateCaptionsAreAlsoDeduplicated() throws IOException {
        // 两帧纯色画面（红色、蓝色）像素不同，指纹快筛拦不住，但模型给出的描述一模一样。
        // 用户实测：不加描述判重会出现两条逐字相同的证据，只是时间戳不同。
        Path dir = Files.createTempDirectory("vse-capdup");
        Path a = blankImage(dir, "a.jpg", new Color(200, 20, 20));
        Path b = blankImage(dir, "b.jpg", new Color(20, 20, 200));
        Map<Path, String> captions = new ConcurrentHashMap<>();
        captions.put(a, "画面是一片纯色的演示背景，中央没有可读文字，属于章节之间的过渡页。");
        captions.put(b, "画面是一片纯色的演示背景，中央没有可读文字，属于章节之间的过渡页。");

        VisualEvidenceService service = new VisualEvidenceService(
                recognizer(Map.of()), new FrameDeduper(), captioner(captions, 24), props());
        VisualEvidenceService.Outcome outcome = service.extract(List.of(
                new com.videostudio.media.FrameExtractor.Frame(a, 10_000, 0),
                new com.videostudio.media.FrameExtractor.Frame(b, 20_000, 1)), p -> { });

        assertEquals(1, outcome.items().size(), "描述相同的画面只保留第一次出现的时刻");
        assertEquals(1, outcome.captioned());
        assertEquals(10_000, outcome.items().get(0).timeMs());
        assertTrue(outcome.dropped() >= 1);
    }

    @Test
    void differentCaptionsAreKept() throws IOException {
        // 判重不能过头：两段不同的描述代表两个不同画面，都要留
        Path dir = Files.createTempDirectory("vse-capdiff");
        Path a = blankImage(dir, "a.jpg", new Color(200, 20, 20));
        Path b = blankImage(dir, "b.jpg", new Color(20, 20, 200));
        Map<Path, String> captions = new ConcurrentHashMap<>();
        captions.put(a, "一位讲师站在白板前讲解架构图");
        captions.put(b, "镜头切到产品界面，屏幕上是一张折线图");

        VisualEvidenceService service = new VisualEvidenceService(
                recognizer(Map.of()), new FrameDeduper(), captioner(captions, 24), props());
        VisualEvidenceService.Outcome outcome = service.extract(List.of(
                new com.videostudio.media.FrameExtractor.Frame(a, 10_000, 0),
                new com.videostudio.media.FrameExtractor.Frame(b, 20_000, 1)), p -> { });

        assertEquals(2, outcome.items().size());
        assertEquals(2, outcome.captioned());
    }
}
