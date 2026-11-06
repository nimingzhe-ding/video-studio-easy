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
    private final ObjectStorage objectStorage = new ObjectStorage();
    private final Redis redis = new Redis();

    public Storage getStorage() {
        return storage;
    }

    public Media getMedia() {
        return media;
    }

    public Upload getUpload() {
        return upload;
    }

    public ObjectStorage getObjectStorage() {
        return objectStorage;
    }


    public Redis getRedis() {
        return redis;
    }

    /** Required Redis coordination settings. */
    public static class Redis {
        /** 锁的持有时间上限：超过即认为持有者已经挂了，避免死锁 */
        private long lockTtlSeconds = 30;
        /** 拿不到锁时最多等多久，等不到就返回 409，而不是无限阻塞 Tomcat 线程 */
        private long lockWaitMillis = 800;
        /** 事件广播保留多久，用于后端实例短暂抖动期间补读 */
        private long eventTtlMinutes = 30;

        public long getLockTtlSeconds() {
            return lockTtlSeconds;
        }

        public void setLockTtlSeconds(long lockTtlSeconds) {
            this.lockTtlSeconds = lockTtlSeconds;
        }

        public long getLockWaitMillis() {
            return lockWaitMillis;
        }

        public void setLockWaitMillis(long lockWaitMillis) {
            this.lockWaitMillis = lockWaitMillis;
        }

        public long getEventTtlMinutes() {
            return eventTtlMinutes;
        }

        public void setEventTtlMinutes(long eventTtlMinutes) {
            this.eventTtlMinutes = eventTtlMinutes;
        }
    }

    public static class Storage {
        /** 运行期数据根目录 */
        private String root = System.getProperty("java.io.tmpdir") + "/video-studio";
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

    /**
     * 对象存储（MinIO / S3 兼容）。
     * <p>这是运行依赖：凭证缺失时启动即失败，不存在「落本地也能跑」的降级模式。
     * 端点、凭证与实际桶在 Compose 里指向私有网络内的服务。
     */
    public static class ObjectStorage {
        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "video-studio";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
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
        /** ASR 分片并发度（由云端接口的 QPS 配额决定） */
        private int asrConcurrency = 3;
        /** 关键帧 OCR 并发度（Vision / tesseract 都可并行，建议 2-4） */
        private int ocrConcurrency = 3;
        /**
         * 是否做说话人分离。默认开启，因为它是纯本地计算（只读 pcm 采样点），
         * 不产生任何外部调用与费用。
         */
        private boolean speakerSeparationEnabled = true;
        /**
         * 两个说话人的音高中位数相差多少半音就判为不同人。
         * 4 个半音约等于大三度，是同一人情绪起伏的上限；再小容易把一个
         * 说话人的激昂段落拆成两个人。
         */
        private double speakerSplitSemitones = 4.0;

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

        public boolean isSpeakerSeparationEnabled() {
            return speakerSeparationEnabled;
        }

        public void setSpeakerSeparationEnabled(boolean speakerSeparationEnabled) {
            this.speakerSeparationEnabled = speakerSeparationEnabled;
        }

        public double getSpeakerSplitSemitones() {
            return speakerSplitSemitones;
        }

        public void setSpeakerSplitSemitones(double speakerSplitSemitones) {
            this.speakerSplitSemitones = speakerSplitSemitones;
        }

        /** 方便日志打印 */
        public String describe() {
            return String.format(Locale.ROOT,
                    "ffmpeg=%s ffprobe=%s ocr=%s scene=%.2f frameInterval=%ds frameMax=%d asrConcurrency=%d ocrConcurrency=%d speaker=%s split=%.1f",
                    ffmpeg, ffprobe, ocr, frameSceneThreshold, frameFallbackIntervalSeconds, frameMaxCount,
                    asrConcurrency, ocrConcurrency, speakerSeparationEnabled ? "on" : "off", speakerSplitSemitones);
        }
    }
}
