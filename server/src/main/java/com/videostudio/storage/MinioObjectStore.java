package com.videostudio.storage;

import com.videostudio.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import io.minio.ListObjectsArgs;
import io.minio.RemoveObjectsArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MinIO 实现。
 *
 * <p>启动时只做一次轻量的 bucket 探测；连不上就把自己标记成不可用并整体降级为
 * {@link LocalObjectStore} 的行为，而不是让应用起不来——容器编排里 MinIO 比应用晚就绪是常态。
 */
public class MinioObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStore.class);

    private final MinioClient client;
    private final String bucket;
    private final ObjectStore fallback;
    private volatile boolean ready;

    public MinioObjectStore(AppProperties.ObjectStorage config) {
        this.client = MinioClient.builder()
                .endpoint(config.getEndpoint())
                .credentials(config.getAccessKey(), config.getSecretKey())
                .build();
        this.bucket = config.getBucket();
        this.fallback = new LocalObjectStore();
        this.ready = bootstrap();
    }

    private boolean bootstrap() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("对象存储：已创建桶 {}", bucket);
            }
            log.info("对象存储：MinIO 就绪 bucket={}", bucket);
            return true;
        } catch (Exception e) {
            log.warn("对象存储：MinIO 不可用，降级为本地磁盘（{}）", e.getMessage());
            return false;
        }
    }

    @Override
    public String provider() {
        return ready ? "minio" : "local";
    }

    @Override
    public boolean enabled() {
        return ready;
    }

    @Override
    public boolean put(String key, Path file, String contentType) {
        if (!ready || key == null || key.isBlank() || !Files.isReadable(file)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(file)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, Files.size(file), -1)
                    .contentType(contentType == null ? ObjectStore.contentTypeOf(key) : contentType)
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("对象存储写入失败 key={}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<Path> materialize(String key, Path target) {
        if (Files.isReadable(target)) {
            return Optional.of(target);
        }
        if (!ready || key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(target);
        } catch (Exception e) {
            log.warn("对象存储读取失败 key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean exists(String key) {
        if (!ready || key == null || key.isBlank()) {
            return false;
        }
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void delete(String key) {
        if (!ready || key == null || key.isBlank()) {
            return;
        }
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            log.warn("对象存储删除失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        if (!ready || prefix == null || prefix.isBlank()) {
            return;
        }
        try {
            List<DeleteObject> targets = new ArrayList<>();
            for (var result : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build())) {
                Item item = result.get();
                targets.add(new DeleteObject(item.objectName()));
            }
            if (targets.isEmpty()) {
                return;
            }
            int removed = 0;
            for (var error : client.removeObjects(RemoveObjectsArgs.builder()
                    .bucket(bucket).objects(targets).build())) {
                DeleteError detail = error.get();
                log.warn("对象存储批量删除失败 key={}: {}", detail.objectName(), detail.message());
                removed++;
            }
            log.info("对象存储：前缀 {} 下删除 {} 个对象（失败 {}）", prefix, targets.size(), removed);
        } catch (Exception e) {
            log.warn("对象存储按前缀删除失败 {}: {}", prefix, e.getMessage());
        }
    }
}
