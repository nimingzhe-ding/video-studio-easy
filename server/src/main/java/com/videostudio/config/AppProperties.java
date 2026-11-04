package com.videostudio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * 全局可调参数。所有字段均可通过 application.properties / 环境变量覆盖。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Storage storage = new Storage();
    private final Media media = new Media();
    private final Upload upload = new Upload();

    public Storage getStorage() {
        return storage;
    }

    public Media getMedia() {
        return media;
    }

    public Upload getUpload() {
        return upload;
    }

    public static class Storage {
        /** 运行期数据根目录 */
        private String root = "./data";
        private long maxUploadBytes = 2L * 1024 * 1024 * 1024;

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }

        public long getMaxUploadBytes() {
            return maxUploadBytes;
        }

        public void setMaxUploadBytes(long maxUploadBytes) {
            this.maxUploadBytes = maxUploadBytes;
        }
    }

    /**
     * 分片上传参数。
     * <p>分片大小默认 8MB：小到断线时最多重传 8MB，大到不会让请求数爆掉
     * （2 小时 1080p 约 1.5GB → 190 片）。
     */
    public static class Upload {
        private int chunkSizeBytes = 8 * 1024 * 1024;
        private int maxChunks = 20000;
        private int retentionHours = 24;

        public int getChunkSizeBytes() {
            return chunkSizeBytes;
        }

        public void setChunkSizeBytes(int chunkSizeBytes) {
            this.chunkSizeBytes = chunkSizeBytes;
        }

        public int getMaxChunks() {
            return maxChunks;
        }

        public void setMaxChunks(int maxChunks) {
            this.maxChunks = maxChunks;
        }

        public int getRetentionHours() {
            return retentionHours;
        }

        public void setRetentionHours(int retentionHours) {
            this.retentionHours = retentionHours;
        }
    }

    public static class Media {
        private String ffmpeg = "ffmpeg";
        private String ffprobe = "ffprobe";
        private String ocr = "tesseract";
        private String ocrLanguages = "chi_sim+eng";
        private int ffmpegTimeoutSeconds = 900;
        private int ocrTimeoutSeconds = 120;
        private double frameSceneThreshold = 0.006;
        private int frameFallbackIntervalSeconds = 20;
        private int frameMaxCount = 400;
        private int frameWidth = 960;
        private int asrSegmentSeconds = 60;
        private int segmentSeconds = 60;
        /** ASR 分片并发度（云端优先，本地 whisper 受 CPU 限制，建议不超过核数的一半） */
        private int asrConcurrency = 3;
        /** 关键帧 OCR 并发度（Vision / tesseract 都可并行，建议 2-4） */
        private int ocrConcurrency = 3;

        public String getFfmpeg() {
            return ffmpeg;
        }

        public void setFfmpeg(String ffmpeg) {
            this.ffmpeg = ffmpeg;
        }

        public String getFfprobe() {
            return ffprobe;
        }

        public void setFfprobe(String ffprobe) {
            this.ffprobe = ffprobe;
        }

        public String getOcr() {
            return ocr;
        }

        public void setOcr(String ocr) {
            this.ocr = ocr;
        }

        public String getOcrLanguages() {
            return ocrLanguages;
        }

        public void setOcrLanguages(String ocrLanguages) {
            this.ocrLanguages = ocrLanguages;
        }

        public int getFfmpegTimeoutSeconds() {
            return ffmpegTimeoutSeconds;
        }

        public void setFfmpegTimeoutSeconds(int ffmpegTimeoutSeconds) {
            this.ffmpegTimeoutSeconds = ffmpegTimeoutSeconds;
        }

        public int getOcrTimeoutSeconds() {
            return ocrTimeoutSeconds;
        }

        public void setOcrTimeoutSeconds(int ocrTimeoutSeconds) {
            this.ocrTimeoutSeconds = ocrTimeoutSeconds;
        }

        public double getFrameSceneThreshold() {
            return frameSceneThreshold;
        }

        public void setFrameSceneThreshold(double frameSceneThreshold) {
            this.frameSceneThreshold = frameSceneThreshold;
        }

        public int getFrameFallbackIntervalSeconds() {
            return frameFallbackIntervalSeconds;
        }

        public void setFrameFallbackIntervalSeconds(int frameFallbackIntervalSeconds) {
            this.frameFallbackIntervalSeconds = frameFallbackIntervalSeconds;
        }

        public int getFrameMaxCount() {
            return frameMaxCount;
        }

        public void setFrameMaxCount(int frameMaxCount) {
            this.frameMaxCount = frameMaxCount;
        }

        public int getFrameWidth() {
            return frameWidth;
        }

        public void setFrameWidth(int frameWidth) {
            this.frameWidth = frameWidth;
        }

        public int getAsrSegmentSeconds() {
            return asrSegmentSeconds;
        }

        public void setAsrSegmentSeconds(int asrSegmentSeconds) {
            this.asrSegmentSeconds = asrSegmentSeconds;
        }

        public int getSegmentSeconds() {
            return segmentSeconds;
        }

        public void setSegmentSeconds(int segmentSeconds) {
            this.segmentSeconds = segmentSeconds;
        }

        public int getAsrConcurrency() {
            return asrConcurrency;
        }

        public void setAsrConcurrency(int asrConcurrency) {
            this.asrConcurrency = asrConcurrency;
        }

        public int getOcrConcurrency() {
            return ocrConcurrency;
        }

        public void setOcrConcurrency(int ocrConcurrency) {
            this.ocrConcurrency = ocrConcurrency;
        }

        /** 方便日志打印 */
        public String describe() {
            return String.format(Locale.ROOT,
                    "ffmpeg=%s ffprobe=%s ocr=%s scene=%.2f frameInterval=%ds frameMax=%d asrConcurrency=%d ocrConcurrency=%d",
                    ffmpeg, ffprobe, ocr, frameSceneThreshold, frameFallbackIntervalSeconds, frameMaxCount,
                    asrConcurrency, ocrConcurrency);
        }
    }
}
