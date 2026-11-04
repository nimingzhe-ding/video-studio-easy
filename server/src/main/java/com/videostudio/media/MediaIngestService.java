package com.videostudio.media;

import com.videostudio.common.ApiException;
import com.videostudio.common.Json;
import com.videostudio.domain.MediaAsset;
import com.videostudio.domain.MediaKind;
import com.videostudio.repository.MediaRepository;
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

    public MediaIngestService(MediaRepository mediaRepository, MediaProbe probe, Storage storage) {
        this.mediaRepository = mediaRepository;
        this.probe = probe;
        this.storage = storage;
    }

    public Optional<MediaAsset> findAsset(long id) {
        return mediaRepository.find(id);
    }

    /** 一次性上传（小文件）：边读边写到媒体目录 */
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

    /** 回写路径 → 探测 → 回写派生字段，两条入口共用 */
    private MediaAsset finish(long id, String name, Path target) {
        mediaRepository.updateStoragePath(id, target.toAbsolutePath().toString());

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
            log.warn("媒体探测失败 {}: {}", name, e.getMessage());
            metaJson = Json.write(Map.of("probeError", String.valueOf(e.getMessage())));
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
