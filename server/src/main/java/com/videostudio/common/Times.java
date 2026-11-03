package com.videostudio.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 时间格式化、解析与引用（citation）提取 */
public final class Times {

    /** 匹配 [mm:ss] 或 [hh:mm:ss] */
    public static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)\\]");

    private Times() {
    }

    public static String format(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    public static String formatBracket(long ms) {
        return "[" + format(ms) + "]";
    }

    /** 人类可读时长：1 小时 23 分 04 秒 */
    public static String human(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h).append(" 小时 ");
        }
        if (m > 0 || h > 0) {
            sb.append(m).append(" 分 ");
        }
        sb.append(s).append(" 秒");
        return sb.toString();
    }

    public static long parse(String text) {
        if (Texts.isBlank(text)) {
            return 0;
        }
        String[] parts = text.trim().split(":");
        long factor = 1000;
        long total = 0;
        for (int i = parts.length - 1; i >= 0; i--) {
            try {
                total += Long.parseLong(parts[i]) * factor;
            } catch (NumberFormatException ignored) {
                return 0;
            }
            factor *= 60;
        }
        return total;
    }

    public static List<String> extractCitations(String markdown) {
        List<String> found = new ArrayList<>();
        if (markdown == null) {
            return found;
        }
        Matcher matcher = CITATION.matcher(markdown);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    public static List<Long> extractCitationMillis(String markdown) {
        return extractCitations(markdown).stream().distinct().map(Times::parse).sorted().toList();
    }

    /** 渲染为前端易于跳转的锚点，例如 00:12:34 */
    public static String anchor(long ms) {
        return format(ms);
    }
}
