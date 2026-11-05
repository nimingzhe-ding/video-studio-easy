package com.videostudio.storage;

import com.videostudio.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配。
 * <p>用 {@code @Bean} 而不是 {@code @Component}：要不要连 MinIO 取决于配置，
 * 未启用时连客户端都不该创建，否则单机模式下平白多一个后台连接与一次网络超时。
 */
@Configuration
public class ObjectStoreConfig {

    @Bean
    public ObjectStore objectStore(AppProperties props) {
        if (!props.getObjectStorage().isEnabled()) {
            return new LocalObjectStore();
        }
        return new MinioObjectStore(props.getObjectStorage());
    }
}
