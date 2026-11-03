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

/**
 * 上传入库：落盘 → 探针 → 回写时长与编码信息。
 * <p>先落盘再探测，避免大文件驻留内存；探测失败时仍然保留资产，便于用户看到失败原因。
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

    public MediaAsset ingest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("请选择要上传的音视频文件");
        }
        String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        MediaKind kind = MediaKind.of(name, file.getContentType());

        MediaAsset placeholder = new MediaAsset(0, name, kind, file.getSize(), 0, null, null, null,
                null, null, "", null, null, null, java.time.Instant.now());
        long id = mediaRepository.insert(placeholder);

        Path target = storage.sourceFile(id, name);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            mediaRepository.delete(id);
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
        mediaRepository.updateStoragePath(id, target.toAbsolutePath().toString());

        String metaJson = null;
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
        log.info("上传完成 id={} name={} kind={} size={} duration={}ms", id, name, kind, saved.sizeBytes(), durationMs);
        return saved;
    }
}
