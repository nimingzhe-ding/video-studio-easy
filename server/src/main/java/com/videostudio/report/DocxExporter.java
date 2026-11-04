package com.videostudio.report;

import com.videostudio.common.Texts;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown → Word（.docx）。
 * <p>只覆盖报告实际用到的语法：标题、无序列表、引用块、表格、分隔线、段落与行内粗体。
 * 这样就不必引入完整的 Markdown 解析库，同时保证导出的文档结构清晰。
 * <p>关键帧图片不写进 Markdown：Markdown 要同时给前端渲染，写死本地绝对路径会让浏览器去请求
 * {@code file://}。图片改由 {@link Figure} 清单旁路传入，只在 Word 里落到「视频证据」之后。
 */
@Component
public class DocxExporter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ORDERED = Pattern.compile("^(\\d+[.、)])\\s+(.*)$");

    /** 插图位置锚点：ReportComposer 生成的证据小节标题 */
    private static final String EVIDENCE_TITLE = "视频证据";
    /** 关键帧在 Word 里的显示宽度（像素），高度按原图比例算 */
    private static final int FIGURE_WIDTH = 420;
    private static final int FIGURE_HEIGHT = 236;

    /** 一张要插进 Word 的关键帧 */
    public record Figure(long timeMs, Path image, String caption) {
    }

    public Path export(String markdown, Path target) throws IOException {
        return export(markdown, target, List.of());
    }

    public Path export(String markdown, Path target, List<Figure> figures) throws IOException {
        Files.createDirectories(target.getParent());
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(target)) {
            List<String> lines = new ArrayList<>(List.of(markdown.split("\n", -1)));
            boolean inEvidence = false;
            boolean figuresDone = false;
            int i = 0;
            while (i < lines.size()) {
                String raw = lines.get(i);
                String line = raw.stripTrailing();
                if (line.isBlank()) {
                    i++;
                    continue;
                }
                if (line.equals("---")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setBorderBottom(org.apache.poi.xwpf.usermodel.Borders.SINGLE);
                    i++;
                    continue;
                }
                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    int level = heading.group(1).length();
                    String title = clean(heading.group(2));
                    // 插图放在「视频证据」这一节结束处：早了会打断列表，晚了和结论脱节
                    if (level <= 2) {
                        if (inEvidence && !figuresDone) {
                            writeFigures(doc, figures);
                            figuresDone = true;
                        }
                        inEvidence = level == 2 && EVIDENCE_TITLE.equals(title);
                        if (inEvidence) {
                            figuresDone = false;
                        }
                    }
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("Heading" + Math.min(4, Math.max(1, level)));
                    XWPFRun run = p.createRun();
                    run.setText(title);
                    run.setBold(true);
                    run.setFontSize(Math.max(12, 20 - level * 2));
                    i++;
                    continue;
                }
                // 表格：连续的 | 行
                if (line.startsWith("|")) {
                    List<String> tableLines = new ArrayList<>();
                    while (i < lines.size() && lines.get(i).stripLeading().startsWith("|")) {
                        tableLines.add(lines.get(i).strip());
                        i++;
                    }
                    writeTable(doc, tableLines);
                    continue;
                }
                if (line.startsWith(">")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(360);
                    XWPFRun run = p.createRun();
                    run.setItalic(true);
                    run.setText(clean(line.replaceFirst("^>\\s*", "")));
                    i++;
                    continue;
                }
                if (line.startsWith("- ") || line.startsWith("* ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(360);
                    p.setSpacingAfter(60);
                    XWPFRun run = p.createRun();
                    run.setText("• ");
                    appendInline(p, line.substring(2));
                    i++;
                    continue;
                }
                Matcher ordered = ORDERED.matcher(line);
                if (ordered.matches()) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(360);
                    p.setSpacingAfter(60);
                    XWPFRun run = p.createRun();
                    run.setText(ordered.group(1) + " ");
                    // 原实现这里传了空串给 appendInline，等于有序列表里的 **粗体** 永远不生效
                    appendInline(p, ordered.group(2));
                    i++;
                    continue;
                }
                XWPFParagraph p = doc.createParagraph();
                p.setAlignment(ParagraphAlignment.LEFT);
                p.setSpacingAfter(80);
                appendInline(p, line);
                i++;
            }
            if (inEvidence && !figuresDone) {
                writeFigures(doc, figures);
            }
            doc.write(out);
        }
        return target;
    }

    /**
     * 插入关键帧：每张图一行（图 + 时间戳 & 说明），宽度按原图比例缩到 {@link #FIGURE_WIDTH}。
     * <p>单张读不出来只跳过这一张——报告的主体是文字，不能因为少了一张截图就整份失败。
     */
    private void writeFigures(XWPFDocument doc, List<Figure> figures) {
        List<Figure> usable = new ArrayList<>();
        for (Figure figure : figures == null ? List.<Figure>of() : figures) {
            if (figure != null && figure.image() != null && Files.isReadable(figure.image())) {
                usable.add(figure);
            }
        }
        if (usable.isEmpty()) {
            return;
        }
        XWPFParagraph title = doc.createParagraph();
        title.setSpacingBefore(120);
        XWPFRun titleRun = title.createRun();
        titleRun.setBold(true);
        titleRun.setText("关键帧（%d 张）".formatted(usable.size()));

        for (Figure figure : usable) {
            if (!insertPicture(doc, figure)) {
                continue;
            }
            XWPFParagraph caption = doc.createParagraph();
            caption.setAlignment(ParagraphAlignment.CENTER);
            caption.setSpacingAfter(160);
            XWPFRun run = caption.createRun();
            run.setFontSize(9);
            run.setItalic(true);
            String text = "[" + com.videostudio.common.Times.format(figure.timeMs()) + "]";
            if (!Texts.isBlank(figure.caption())) {
                text += " " + Texts.oneLine(figure.caption(), 160);
            }
            run.setText(text);
        }
    }

    private boolean insertPicture(XWPFDocument doc, Figure figure) {
        try (InputStream in = Files.newInputStream(figure.image())) {
            BufferedImage image = ImageIO.read(figure.image().toFile());
            int width = FIGURE_WIDTH;
            int height = FIGURE_HEIGHT;
            if (image != null && image.getWidth() > 0) {
                height = (int) Math.round((double) FIGURE_WIDTH * image.getHeight() / image.getWidth());
            }
            XWPFParagraph p = doc.createParagraph();
            p.setAlignment(ParagraphAlignment.CENTER);
            p.setSpacingBefore(120);
            XWPFRun run = p.createRun();
            run.addPicture(in, Document.PICTURE_TYPE_JPEG, figure.image().getFileName().toString(),
                    Units.toEMU(width), Units.toEMU(height));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeTable(XWPFDocument doc, List<String> lines) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.matches("^\\|\\s*-{2,}.*")) {
                continue;
            }
            String body = line.substring(1, Math.max(1, line.length() - 1));
            List<String> cells = new ArrayList<>();
            for (String cell : body.split("\\|")) {
                cells.add(clean(cell.strip()));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        var table = doc.createTable(rows.size(), Math.max(1, columns));
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            for (int c = 0; c < columns; c++) {
                String text = c < cells.size() ? cells.get(c) : "";
                var cell = table.getRow(r).getCell(c);
                if (cell == null) {
                    continue;
                }
                var p = cell.getParagraphs().get(0);
                var run = p.createRun();
                run.setText(text);
                run.setBold(r == 0);
                run.setFontSize(10);
            }
        }
        doc.createParagraph();
    }

    /** 处理 **粗体** 行内标记 */
    private void appendInline(XWPFParagraph paragraph, String text) {
        Matcher matcher = BOLD.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                paragraph.createRun().setText(text.substring(cursor, matcher.start()));
            }
            XWPFRun bold = paragraph.createRun();
            bold.setBold(true);
            bold.setText(matcher.group(1));
            cursor = matcher.end();
        }
        if (cursor < text.length()) {
            paragraph.createRun().setText(text.substring(cursor));
        }
    }

    private String clean(String text) {
        return Texts.isBlank(text) ? "" : text.replace("\\|", "|").strip();
    }
}
