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

    public Storage getStorage() {
        return storage;
    }

    public Media getMedia() {
        return media;
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

        /** 方便日志打印 */
        public String describe() {
            return String.format(Locale.ROOT,
                    "ffmpeg=%s ffprobe=%s ocr=%s scene=%.2f frameInterval=%ds frameMax=%d",
                    ffmpeg, ffprobe, ocr, frameSceneThreshold, frameFallbackIntervalSeconds, frameMaxCount);
        }
    }
}
