package com.videostudio.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** 未配置对象存储时的默认实现：文件本来就在本地，不需要做任何搬运 */
public class LocalObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(LocalObjectStore.class);

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public boolean put(String key, Path file, String contentType) {
        return true;
    }

    @Override
    public Optional<Path> materialize(String key, Path target) {
        return Files.isReadable(target) ? Optional.of(target) : Optional.empty();
    }

    @Override
    public boolean exists(String key) {
        return false;
    }

    @Override
    public void delete(String key) {
        // 本地模式下删除由 CleanupService 直接操作目录
    }

    @Override
    public void deletePrefix(String prefix) {
        // 同上
    }

    /** 仅用于测试与排查：确保目录存在，失败时给出可读错误 */
    static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("创建目录失败 {}: {}", dir, e.getMessage());
        }
    }
}
