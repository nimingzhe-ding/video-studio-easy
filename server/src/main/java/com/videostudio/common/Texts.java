package com.videostudio.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 文本处理：分词（中英混排）、关键词抽取、词项打分、截断 */
public final class Texts {

    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "在", "是", "和", "与", "就", "都", "而", "及", "或", "一个", "我们", "他们",
            "这个", "那个", "这样", "那么", "因为", "所以", "但是", "如果", "可以", "没有", "什么",
            "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "were", "be", "been",
            "it", "this", "that", "for", "on", "with", "as", "at", "by", "from", "we", "you", "they",
            "i", "he", "she", "but", "not", "so", "if", "then", "than", "there", "here", "have", "has");

    private Texts() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String trim(String s) {
        return s == null ? "" : s.strip();
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max)) + "…";
    }

    /** 合并多余空白，去掉连续空行，保留段落结构 */
    public static String normalizeWhitespace(String s) {
        if (s == null) {
            return "";
        }
        String text = s.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("[ \\t\\x0B\\f]+", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }

    public static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    /**
     * 中英混排分词：英文按单词切分，中文取「单字 + 相邻二字」以提高召回。
     */
    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (isBlank(text)) {
            return out;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder latin = new StringBuilder();
        char prevCjk = 0;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                if (!latin.isEmpty()) {
                    addLatin(out, latin);
                }
                if (prevCjk != 0) {
                    out.add(new String(new char[]{prevCjk, c}));
                }
                out.add(String.valueOf(c));
                prevCjk = c;
            } else if (Character.isLetterOrDigit(c)) {
                if (c < 128) {
                    latin.append(c);
                } else if (!latin.isEmpty()) {
                    addLatin(out, latin);
                }
                prevCjk = 0;
            } else {
                if (!latin.isEmpty()) {
                    addLatin(out, latin);
                }
                prevCjk = 0;
            }
        }
        if (!latin.isEmpty()) {
            addLatin(out, latin);
        }
        return out;
    }

    private static void addLatin(List<String> out, StringBuilder buffer) {
        String word = buffer.toString();
        buffer.setLength(0);
        if (word.length() >= 2 && !STOPWORDS.contains(word)) {
            out.add(word);
        }
    }

    /** 词频关键词，过滤停用词与单字噪声 */
    public static List<String> keywords(String text, int limit) {
        List<String> tokens = tokens(text);
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (String t : tokens) {
            if (STOPWORDS.contains(t)) {
                continue;
            }
            if (t.length() == 1 && isCjk(t.charAt(0))) {
                continue;
            }
            counts.merge(t, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            for (String t : tokens) {
                counts.merge(t, 1, Integer::sum);
            }
        }
        List<String> result = new ArrayList<>(counts.keySet());
        result.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));
        Set<String> unique = new LinkedHashSet<>();
        for (String k : result) {
            if (unique.size() >= limit) {
                break;
            }
            // 去除被更高频词包含的单字
            boolean subsumed = unique.stream().anyMatch(existing -> existing.contains(k) && existing.length() > k.length());
            if (!subsumed) {
                unique.add(k);
            }
        }
        return new ArrayList<>(unique);
    }

    /** 命中率：词项在文本中出现的比例（0..1） */
    public static double termScore(List<String> terms, String text) {
        if (terms == null || terms.isEmpty() || isBlank(text)) {
            return 0;
        }
        String hay = text.toLowerCase(Locale.ROOT);
        int hit = 0;
        for (String term : terms) {
            if (!isBlank(term) && hay.contains(term.toLowerCase(Locale.ROOT))) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }

    public static String joinNonBlank(String joiner, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (!isBlank(p)) {
                kept.add(p.strip());
            }
        }
        return String.join(joiner, kept);
    }

    public static String oneLine(String text, int max) {
        if (text == null) {
            return "";
        }
        return truncate(text.replaceAll("\\s+", " ").strip(), max);
    }
}
