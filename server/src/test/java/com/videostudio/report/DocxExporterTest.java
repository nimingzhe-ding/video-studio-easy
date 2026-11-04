package com.videostudio.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Word 导出测试。
 * <p>这里主要是锁住两件容易静默失效的事：图片到底有没有真的写进 docx（POI 不会因为图片丢而报错），
 * 以及图片插在「视频证据」之后而不是文档末尾或标题中间。
 */
class DocxExporterTest {

    private static final String MARKDOWN = """
            # 示例报告

            | 项目 | 内容 |
            | --- | --- |
            | 分析模式 | 通用理解 |

            ## 核心结论

            - 第一章讲了为什么需要长视频理解

            ## 视频证据

            - [00:00] OCR：第一章 为什么需要长视频理解
            - [00:18] OCR：第二章 关键设计

            ## 内容脉络

            - 开场先抛出问题

            ---

            > 本报告由 video-studio-easy 自动生成。
            """;

    private static Path frame(Path dir, String name, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(19, 26, 38));
        g.fillRect(0, 0, width, height);
        g.dispose();
        Path file = dir.resolve(name);
        ImageIO.write(image, "jpg", file.toFile());
        return file;
    }

    @Test
    void figuresAreEmbeddedNextToEvidenceSection(@TempDir Path dir) throws Exception {
        Path first = frame(dir, "scene-0001.jpg", 960, 540);
        Path second = frame(dir, "scene-0002.jpg", 640, 480);
        Path target = dir.resolve("report.docx");

        List<DocxExporter.Figure> figures = List.of(
                new DocxExporter.Figure(0, first, "第一章 为什么需要长视频理解"),
                new DocxExporter.Figure(18_000, second, "第二章 关键设计"));

        new DocxExporter().export(MARKDOWN, target, figures);

        assertTrue(Files.size(target) > 0, "docx 不应为空文件");
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(target))) {
            assertEquals(2, doc.getAllPictures().size(), "两张关键帧都应写进文档");
            List<String> texts = doc.getParagraphs().stream()
                    .map(DocxExporterTest::text)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
            int evidence = texts.indexOf("视频证据");
            int figuresTitle = texts.indexOf("关键帧（2 张）");
            int nextSection = texts.indexOf("内容脉络");
            assertTrue(evidence >= 0, "缺少视频证据小节：" + texts);
            assertTrue(figuresTitle > evidence, "关键帧应插在视频证据之后：" + texts);
            assertTrue(nextSection > figuresTitle, "关键帧应插在下一小节之前：" + texts);
            assertTrue(texts.stream().anyMatch(s -> s.contains("[00:18] 第二章 关键设计")),
                    "图片下方应带时间戳与说明：" + texts);
        }
    }

    @Test
    void missingImageFilesAreSkippedInsteadOfFailing(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("report.docx");
        List<DocxExporter.Figure> figures = List.of(
                new DocxExporter.Figure(0, dir.resolve("not-there.jpg"), "已被清理的帧"));

        new DocxExporter().export(MARKDOWN, target, figures);

        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(target))) {
            assertEquals(0, doc.getAllPictures().size());
            assertTrue(doc.getParagraphs().stream().map(DocxExporterTest::text)
                            .noneMatch(s -> s.startsWith("关键帧（")),
                    "一张都插不进去时不该留一个空标题");
        }
    }

    @Test
    void noFiguresKeepsDocumentIdentical(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("report.docx");
        new DocxExporter().export(MARKDOWN, target);
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(target))) {
            assertEquals(0, doc.getAllPictures().size());
            List<String> texts = doc.getParagraphs().stream().map(DocxExporterTest::text)
                    .filter(s -> !s.isBlank()).toList();
            assertFalse(texts.contains("视频证据") == false, "正文结构不应被插图逻辑破坏：" + texts);
        }
    }

    @Test
    void orderedListKeepsBoldInline(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("report.docx");
        new DocxExporter().export("""
                # 标题

                1. 第一步是**解析媒体**
                """, target);
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(target))) {
            XWPFParagraph paragraph = doc.getParagraphs().stream()
                    .filter(p -> text(p).startsWith("1."))
                    .findFirst().orElseThrow();
            assertTrue(paragraph.getRuns().stream().anyMatch(r -> r.isBold() && "解析媒体".equals(r.text())),
                    "有序列表里的 **粗体** 应被解析：" + paragraph.getRuns().stream().map(XWPFRun::text).toList());
        }
    }

    private static String text(XWPFParagraph paragraph) {
        return paragraph.getRuns().stream().map(XWPFRun::text).filter(java.util.Objects::nonNull)
                .collect(Collectors.joining()).strip();
    }
}
