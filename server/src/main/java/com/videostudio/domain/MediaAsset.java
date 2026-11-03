package com.videostudio.domain;

import java.time.Instant;

/** 媒体资产（上传的原文件 + 派生文件位置） */
public record MediaAsset(
        long id,
        String name,
        MediaKind kind,
        long sizeBytes,
        long durationMs,
        Integer width,
        Integer height,
        Double fps,
        String videoCodec,
        String audioCodec,
        String storagePath,
        String audioPath,
        String frameDir,
        String metaJson,
        Instant createdAt
) {
    public boolean hasVideo() {
        return kind == MediaKind.VIDEO;
    }
}
