package com.videostudio.report;

import com.videostudio.common.Texts;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
 */
@Component
public class DocxExporter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    public Path export(String markdown, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(target)) {
            List<String> lines = new ArrayList<>(List.of(markdown.split("\n", -1)));
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
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("Heading" + Math.min(4, Math.max(1, level)));
                    XWPFRun run = p.createRun();
                    run.setText(clean(heading.group(2)));
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
                if (line.matches("^\\d+[.、)]\\s+.*")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(360);
                    XWPFRun run = p.createRun();
                    run.setText(line.replaceFirst("^\\s+", ""));
                    appendInline(p, "");
                    i++;
                    continue;
                }
                XWPFParagraph p = doc.createParagraph();
                p.setAlignment(ParagraphAlignment.LEFT);
                p.setSpacingAfter(80);
                appendInline(p, line);
                i++;
            }
            doc.write(out);
        }
        return target;
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
