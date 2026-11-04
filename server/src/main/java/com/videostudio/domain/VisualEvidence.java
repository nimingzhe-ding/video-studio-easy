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

    /** 有 OCR 文字就用文字，否则退回视觉描述；两者都没有返回空串 */
    public String displayText() {
        if (ocrText != null && !ocrText.isBlank()) {
            return ocrText;
        }
        return caption == null ? "" : caption;
    }

    /** 这条证据是不是只能靠视觉描述（OCR 没读到任何文字） */
    public boolean captionOnly() {
        return (ocrText == null || ocrText.isBlank()) && caption != null && !caption.isBlank();
    }
}
