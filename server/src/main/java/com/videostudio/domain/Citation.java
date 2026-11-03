package com.videostudio.domain;

/** 页面/API 上展示的引用：时间戳 + 出处 */
public record Citation(
        long timeMs,
        String label,
        String source,
        String snippet
) {
    public static Citation of(long timeMs, String source, String snippet) {
        return new Citation(timeMs, com.videostudio.common.Times.format(timeMs), source, snippet);
    }
}
