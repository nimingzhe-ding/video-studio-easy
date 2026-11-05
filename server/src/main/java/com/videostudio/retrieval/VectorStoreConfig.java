package com.videostudio.retrieval;

import com.videostudio.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量库装配。
 * <p>和对象存储同理：启用才建客户端，单机模式连一次连接都不该多花。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(AppProperties props) {
        if (!props.getVector().isEnabled()) {
            return new MemoryVectorStore();
        }
        return new MilvusVectorStore(props.getVector());
    }
}
