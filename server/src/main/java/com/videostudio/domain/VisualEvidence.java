package com.videostudio.domain;

/** 画面证据：关键帧 + OCR 文本 + 可选的视觉描述 */
public record VisualEvidence(
        long timeMs,
        String framePath,
        String ocrText,
        String caption,
        String hash,
        String source
) {
    public String bracket() {
        return "[" + com.videostudio.common.Times.format(timeMs) + "]";
    }
}
