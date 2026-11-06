package com.videostudio.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 对象存储抽象：MinIO / S3 兼容。
 *
 * <p><b>为什么关键帧、音频、报告仍然先落本地再上传？</b>
 * ffmpeg / tesseract 都是按本地路径取文件的命令行工具，把中间产物改成流式对象会
 * 让整条媒体流水线复杂一个量级，而收益只是省几 MB 临时盘。所以这里的定位是
 * 「本地盘是工作区，对象存储是持久层」：算完就传上去，容器重建后还能把源文件拉回来。
 *
 * <p>对象存储是<b>运行依赖</b>，不是可选降级项：凭证缺失时 {@link MinioObjectStore}
 * 直接拒绝启动；写入失败会抛异常让任务明确失败，而不是留下「数据库说有、桶里没有」的记录。
 */
public interface ObjectStore {

    /** 当前实现，用于日志与 /api/jobs/runtime 展示 */
    String provider();

    boolean enabled();

    /** 上传本地文件；失败抛 {@link IllegalStateException}，由调用方决定任务是否失败 */
    boolean put(String key, Path file, String contentType);

    /**
     * 确保 key 对应的文件在本地可用：本地已有就直接返回，否则从对象存储拉到 target。
     * 返回空表示拿不到（未启用 / 对象不存在 / 拉取失败）。
     */
    Optional<Path> materialize(String key, Path target);

    boolean exists(String key);

    /** 删除单个对象，用于任务级清理 */
    void delete(String key);

    /** 删除前缀下全部对象，用于删除整个媒体 */
    void deletePrefix(String prefix);

    static String contentTypeOf(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".m4v")) {
            return "video/x-m4v";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown; charset=utf-8";
        }
        return "application/octet-stream";
    }

    static long sizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }
}
