package com.videostudio.media;

import com.videostudio.common.ApiException;
import com.videostudio.common.Json;
import com.videostudio.domain.MediaAsset;
import com.videostudio.domain.MediaKind;
import com.videostudio.repository.MediaRepository;
import com.videostudio.storage.MediaObjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 上传入库：落盘 → 探针 → 回写时长与编码信息。
 * <p>先落盘再探测，避免大文件驻留内存；探测失败时仍然保留资产，便于用户看到失败原因。
 *
 * <p>两个入口共用同一段收尾逻辑：
 * <ul>
 *   <li>{@link #ingest(MultipartFile)}：小文件一次性上传；</li>
 *   <li>{@link #ingestFile(Path, String)}：分片合并后的整文件，直接搬进媒体目录，不重复拷贝。</li>
 * </ul>
 */
@Service
public class MediaIngestService {

    private static final Logger log = LoggerFactory.getLogger(MediaIngestService.class);

    private final MediaRepository mediaRepository;
    private final MediaProbe probe;
    private final Storage storage;
    private final MediaObjects objects;
    private final YtDlpDownloader downloader;

    public MediaIngestService(MediaRepository mediaRepository, MediaProbe probe, Storage storage,
                              MediaObjects objects, YtDlpDownloader downloader) {
        this.mediaRepository = mediaRepository;
        this.probe = probe;
        this.storage = storage;
        this.objects = objects;
        this.downloader = downloader;
    }

    public Optional<MediaAsset> findAsset(long id) {
        return mediaRepository.find(id);
    }

    /** 一次性上传（小文件）：边读边写到媒体目录 */
    @org.springframework.transaction.annotation.Transactional
    public MediaAsset ingest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("请选择要上传的音视频文件");
        }
        String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        long id = createPlaceholder(name, MediaKind.of(name, file.getContentType()), file.getSize());

        Path target = storage.sourceFile(id, name);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            mediaRepository.delete(id);
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
        return finish(id, name, target);
    }

    /**
     * 分片合并后的整文件入库。
     * <p>用 {@code move} 而不是 {@code copy}：默认都在同一个数据盘，移动是原子的且不占额外空间；
     * 只有跨盘（assembled 在临时目录）时才退化成拷贝。
     */
    public MediaAsset ingestFile(Path assembled, String name) {
        if (assembled == null || !Files.isReadable(assembled)) {
            throw new ApiException("合并后的文件不可读: " + assembled);
        }
        String safe = name == null || name.isBlank() ? assembled.getFileName().toString() : name;
        long size;
        try {
            size = Files.size(assembled);
        } catch (IOException e) {
            throw new IllegalStateException("读取合并文件大小失败: " + e.getMessage(), e);
        }
        long id = createPlaceholder(safe, MediaKind.of(safe, null), size);
        Path target = storage.sourceFile(id, safe);
        try {
            try {
                Files.move(assembled, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                log.debug("跨盘移动失败，退化为拷贝: {}", moveFailed.getMessage());
                Files.copy(assembled, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(assembled);
            }
        } catch (IOException e) {
            mediaRepository.delete(id);
            throw new IllegalStateException("写入媒体文件失败: " + e.getMessage(), e);
        }
        return finish(id, safe, target);
    }

    private long createPlaceholder(String name, MediaKind kind, long size) {
        MediaAsset placeholder = new MediaAsset(0, name, kind, size, 0, null, null, null,
                null, null, "", null, null, null, java.time.Instant.now());
        return mediaRepository.insert(placeholder);
    }

    /**
     * 从网页链接导入：下载 → 探测 → 入库。
     *
     * <p>保存的文件名来自链接的主机与标题，而不是 yt-dlp 的随机名——
     * 用户在素材列表里要能认出这是哪条视频。下载文件是临时工作副本，
     * 入库（含上传到对象存储）完成后立即删除。
     */
    public MediaAsset ingestUrl(String url) {
        if (!downloader.enabled()) {
            throw new ApiException("未配置 yt-dlp，无法从链接导入视频");
        }
        String name = sourceName(url);
        YtDlpDownloader.Downloaded downloaded =
                downloader.download(url, storage.root().resolve("url-import"));
        try {
            Path temp = downloaded.file();
            long size = downloaded.bytes();
            long id = createPlaceholder(name, MediaKind.of(name, null), size);
            Path target = storage.sourceFile(id, name);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                // 跨盘时退化成拷贝
                try {
                    Files.copy(temp, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    mediaRepository.delete(id);
                    throw new IllegalStateException("写入媒体文件失败: " + e.getMessage(), e);
                }
            }
            return finish(id, name, target);
        } finally {
            // 临时文件只是搬运工，内容已经在媒体目录与对象存储里
            try {
                Files.deleteIfExists(downloaded.file());
            } catch (IOException ignored) {
                log.warn("临时下载文件清理失败: {}", downloaded.file());
            }
        }
    }

    /** 由链接派生一个用户能看懂的文件名，扩展名固定 mp4 */
    private static String sourceName(String url) {
        String host;
        try {
            host = java.net.URI.create(url.strip()).getHost();
        } catch (RuntimeException e) {
            host = null;
        }
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now());
        String base = (host == null || host.isBlank() ? "web" : host.replaceAll("[^A-Za-z0-9.-]", "-"))
                + "-" + stamp;
        return base.length() > 120 ? base.substring(0, 120) + ".mp4" : base + ".mp4";
    }

    /** 回写路径 → 探测 → 回写派生字段，两条入口共用 */
    private MediaAsset finish(long id, String name, Path target) {
        mediaRepository.updateStoragePath(id, objects.sourceKey(id,name));
        // 先入库再探测：即使后续分析失败，重新分析的请求也能从桶里拿回源文件
        objects.putSource(id, name, target);

        String metaJson;
        long durationMs = 0;
        Integer width = null;
        Integer height = null;
        Double fps = null;
        String videoCodec = null;
        String audioCodec = null;
        try {
            MediaInfo info = probe.probe(target.toString());
            durationMs = info.durationMs();
            width = info.width();
            height = info.height();
            fps = info.fps();
            videoCodec = info.videoCodec();
            audioCodec = info.audioCodec();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("hasVideo", info.hasVideo());
            meta.put("hasAudio", info.hasAudio());
            meta.put("probedAt", java.time.Instant.now().toString());
            metaJson = Json.write(meta);
        } catch (RuntimeException e) {
            objects.deleteAsset(id);
            mediaRepository.delete(id);
            throw new com.videostudio.common.ApiException("文件无法解析，请确认上传的是有效音视频文件");
        }

        mediaRepository.updateDerived(id, durationMs, width, height, fps, videoCodec, audioCodec,
                null, null, metaJson);
        MediaAsset saved = mediaRepository.find(id)
                .orElseThrow(() -> new IllegalStateException("媒体记录写入后丢失: " + id));
        log.info("入库完成 id={} name={} kind={} size={} duration={}ms",
                id, name, saved.kind(), saved.sizeBytes(), durationMs);
        return saved;
    }
}
