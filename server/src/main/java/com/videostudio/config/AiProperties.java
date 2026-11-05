package com.videostudio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/** 模型网关配置（OpenAI 兼容协议） */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String apiKey = "";
    private String model = "qwen-plus";
    private String audioModel = "qwen-audio-asr";
    private String visionModel = "qwen-vl-max";
    private String embeddingModel = "text-embedding-v3";
    private String embeddingBaseUrl = "";
    private String embeddingApiKey = "";
    private int timeoutSeconds = 180;
    private int maxRetries = 3;
    /**
     * 是否允许对关键帧调用视觉模型写描述。
     * <p>默认关闭：视觉模型按帧计费，长视频几百帧全量调用成本不可控，
     * 参考项目也明确「只对低置信度或强视觉任务定向调用」。打开后也只补 OCR 文字极少的帧。
     */
    private boolean visionCaptionEnabled = false;
    /** 单个任务最多为多少帧生成视觉描述 */
    private int visionCaptionMaxFrames = 24;

    /** 是否具备真实调用能力 */
    public boolean cloudEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean visionEnabled() {
        return cloudEnabled();
    }

    /** 视觉描述是否可用：既要模型可达，也要用户显式打开开关 */
    public boolean visionCaptionEnabled() {
        return visionEnabled() && visionCaptionEnabled;
    }

    public int visionCaptionMaxFrames() {
        return Math.max(0, Math.min(visionCaptionMaxFrames, 200));
    }

    public boolean embeddingEnabled() {
        return cloudEnabled() && embeddingModel != null && !embeddingModel.isBlank();
    }

    public String resolvedEmbeddingBaseUrl() {
        return embeddingBaseUrl == null || embeddingBaseUrl.isBlank() ? baseUrl : embeddingBaseUrl;
    }

    public String resolvedEmbeddingApiKey() {
        return embeddingApiKey == null || embeddingApiKey.isBlank() ? apiKey : embeddingApiKey;
    }

    public String chatUrl() {
        return trimSlash(baseUrl) + "/chat/completions";
    }

    public String embeddingUrl() {
        return trimSlash(resolvedEmbeddingBaseUrl()) + "/embeddings";
    }

    private String trimSlash(String url) {
        String u = url == null ? "" : url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public String describe() {
        return String.format(Locale.ROOT, "cloud=%s model=%s vision=%s embedding=%s",
                cloudEnabled(), model, visionModel, embeddingModel);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAudioModel() {
        return audioModel;
    }

    public void setAudioModel(String audioModel) {
        this.audioModel = audioModel;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public void setEmbeddingApiKey(String embeddingApiKey) {
        this.embeddingApiKey = embeddingApiKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public boolean isVisionCaptionEnabled() {
        return visionCaptionEnabled;
    }

    public void setVisionCaptionEnabled(boolean visionCaptionEnabled) {
        this.visionCaptionEnabled = visionCaptionEnabled;
    }

    public int getVisionCaptionMaxFrames() {
        return visionCaptionMaxFrames;
    }

    public void setVisionCaptionMaxFrames(int visionCaptionMaxFrames) {
        this.visionCaptionMaxFrames = visionCaptionMaxFrames;
    }
}
