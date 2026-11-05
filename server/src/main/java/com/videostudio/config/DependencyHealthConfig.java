package com.videostudio.config;

import com.videostudio.retrieval.QdrantVectorStore;
import com.videostudio.storage.MinioObjectStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DependencyHealthConfig {
    @Bean HealthIndicator qdrant(QdrantVectorStore vectors) {
        return () -> { try { vectors.health(); return Health.up().build(); } catch(Exception ex) { return Health.down().build(); } };
    }
    @Bean HealthIndicator objectStore(MinioObjectStore store) {
        return () -> { try { store.health(); return Health.up().build(); } catch(Exception ex) { return Health.down().build(); } };
    }
}
