package com.videostudio.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProductionRequirements {
    private final AiProperties ai; private final String datasource; private final String asr;
    public ProductionRequirements(AiProperties ai,@Value("${spring.datasource.url}") String datasource,
                                  @Value("${ai.asr-url}") String asr) { this.ai=ai; this.datasource=datasource; this.asr=asr; }
    @PostConstruct void validate() {
        if(!datasource.startsWith("jdbc:mysql:")) throw new IllegalStateException("生产版本仅支持 MySQL，请配置 DB_URL");
        if(!ai.cloudEnabled() || ai.getModel().isBlank() || ai.getEmbeddingModel().isBlank()
            || ai.resolvedEmbeddingApiKey().isBlank() || asr.isBlank()) throw new IllegalStateException("必须配置真实的模型、Embedding 与云端 ASR 服务");
        if(ai.getTimeoutSeconds()<1 || ai.getMaxRetries()<1) throw new IllegalStateException("模型超时与重试次数必须为正数");
    }
}
