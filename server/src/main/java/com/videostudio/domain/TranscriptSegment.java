package com.videostudio.domain;

/** 统一时间轴片段：语音转写或画面文字，二者在 60s 粒度上对齐 */
public record TranscriptSegment(
        long startMs,
        long endMs,
        String text,
        String speaker,
        Double confidence,
        String source
) {
    public String bracket() {
        return "[" + com.videostudio.common.Times.format(startMs) + " - " + com.videostudio.common.Times.format(endMs) + "]";
    }
}
