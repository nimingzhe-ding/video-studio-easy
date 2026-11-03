package com.videostudio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 云端实时多模态模型直通配置 */
@ConfigurationProperties(prefix = "realtime")
public class RealtimeProperties {

    private boolean enabled = true;
    private String url = "";
    private String apiKey = "";
    private String model = "qwen3-omni-flash-realtime";

    public boolean ready() {
        return enabled && url != null && !url.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
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
}
