package com.videostudio.media;

/** ffprobe 解析结果 */
public record MediaInfo(
        long durationMs,
        Integer width,
        Integer height,
        Double fps,
        String videoCodec,
        String audioCodec,
        boolean hasVideo,
        boolean hasAudio
) {
    public static MediaInfo audioOnly(long durationMs, String codec) {
        return new MediaInfo(durationMs, null, null, null, null, codec, false, true);
    }
}
