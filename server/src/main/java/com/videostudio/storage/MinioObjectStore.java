package com.videostudio.storage;

import com.videostudio.config.AppProperties;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

@Component
public class MinioObjectStore implements ObjectStore {
    private final MinioClient client; private final String bucket;
    public MinioObjectStore(AppProperties props) {
        var config=props.getObjectStorage();
        if(config.getAccessKey()==null || config.getAccessKey().isBlank() || config.getSecretKey()==null || config.getSecretKey().isBlank())
            throw new IllegalStateException("必须配置对象存储凭证");
        client=MinioClient.builder().endpoint(config.getEndpoint()).credentials(config.getAccessKey(),config.getSecretKey())
            .httpClient(new okhttp3.OkHttpClient.Builder().connectTimeout(java.time.Duration.ofSeconds(5))
                .readTimeout(java.time.Duration.ofSeconds(15)).writeTimeout(java.time.Duration.ofSeconds(30)).build()).build();
        bucket=config.getBucket();
    }
    @PostConstruct public void health() {
        try { if(!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) throw new IllegalStateException("对象存储桶不存在，请先初始化"); }
        catch(Exception ex) { throw new IllegalStateException("对象存储不可用",ex); }
    }
    public String provider() { return "s3"; }
    public boolean enabled() { return true; }
    public boolean put(String key,Path file,String contentType) {
        try(InputStream in=Files.newInputStream(file)) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(in,Files.size(file),-1)
                .contentType(contentType==null?ObjectStore.contentTypeOf(key):contentType).build());
            return true;
        } catch(Exception ex) { throw new IllegalStateException("对象存储写入失败",ex); }
    }
    public Optional<Path> materialize(String key,Path target) {
        if(Files.isRegularFile(target)) return Optional.of(target);
        Path tmp=target.resolveSibling(target.getFileName()+"."+UUID.randomUUID()+".part");
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            try(InputStream in=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                Files.copy(in,tmp,StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(target);
        } catch(ErrorResponseException ex) {
            if("NoSuchKey".equals(ex.errorResponse().code())) return Optional.empty();
            throw new IllegalStateException("对象存储读取失败",ex);
        } catch(Exception ex) { throw new IllegalStateException("对象存储读取失败",ex); }
        finally { try { Files.deleteIfExists(tmp); } catch(java.io.IOException ignored) {} }
    }
    public boolean exists(String key) {
        try { client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()); return true; }
        catch(ErrorResponseException ex) {
            if("NoSuchKey".equals(ex.errorResponse().code())) return false;
            throw new IllegalStateException("对象存储检查失败",ex);
        } catch(Exception ex) { throw new IllegalStateException("对象存储检查失败",ex); }
    }
    public void delete(String key) {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch(Exception ex) { throw new IllegalStateException("对象存储删除失败",ex); }
    }
    public void deletePrefix(String prefix) {
        if(prefix==null || prefix.isBlank()) throw new IllegalArgumentException("不允许删除空前缀");
        try {
            for(var result:client.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) delete(result.get().objectName());
        } catch(Exception ex) { throw new IllegalStateException("对象存储清理失败",ex); }
    }
}
