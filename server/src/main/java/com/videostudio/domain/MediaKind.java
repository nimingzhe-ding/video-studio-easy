package com.videostudio.domain;

/** 媒体类型 */
public enum MediaKind {
    VIDEO,
    AUDIO;

    public static MediaKind of(String filename, String contentType) {
        String name = filename == null ? "" : filename.toLowerCase();
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.startsWith("audio/") || name.matches(".*\\.(mp3|wav|m4a|aac|flac|ogg|opus|wma|amr|aiff)$")) {
            return AUDIO;
        }
        return VIDEO;
    }
}
