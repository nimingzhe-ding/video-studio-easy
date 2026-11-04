package com.videostudio.web;

import com.videostudio.common.ApiException;
import com.videostudio.common.Texts;
import com.videostudio.config.AppProperties;
import com.videostudio.domain.MediaAsset;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.media.ProcessRunner;
import com.videostudio.media.Storage;
import com.videostudio.repository.MediaRepository;
import com.videostudio.repository.VisualRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 媒体与证据的二进制输出：原片播放、缩略图、证据帧。
 * <p>为什么不用静态资源映射：素材路径由数据库记录、且需要支持 Range 断点续播，
 * 交给控制器可以同时处理「记录不存在」「文件被手工删除」这类情况并返回可读错误。
 */
@RestController
public class MediaStreamController {

    private static final Logger log = LoggerFactory.getLogger(MediaStreamController.class);
    private static final int BUFFER = 64 * 1024;

    private final MediaRepository mediaRepository;
    private final VisualRepository visualRepository;
    private final Storage storage;
    private final ProcessRunner runner;
    private final AppProperties props;

    public MediaStreamController(MediaRepository mediaRepository, VisualRepository visualRepository,
                                 Storage storage, ProcessRunner runner, AppProperties props) {
        this.mediaRepository = mediaRepository;
        this.visualRepository = visualRepository;
        this.storage = storage;
        this.runner = runner;
        this.props = props;
    }

    /** 原片流：支持 Range，浏览器可以直接拖进度条 */
    @GetMapping("/api/media/{id}/stream")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable long id,
                                                        @RequestHeader(value = "Range", required = false) String range) {
        MediaAsset asset = requireAsset(id);
        Path file = existingSource(asset);
        long total = size(file);
        MediaType type = MediaTypeFactory.getMediaType(asset.name()).orElse(MediaType.APPLICATION_OCTET_STREAM);

        long start = 0;
        long end = total - 1;
        boolean partial = false;
        if (!Texts.isBlank(range) && range.startsWith("bytes=")) {
            long[] parsed = parseRange(range, total);
            if (parsed == null) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                        .build();
            }
            start = parsed[0];
            end = parsed[1];
            partial = true;
        }
        long length = end - start + 1;

        // lambda 只能捕获 final 变量，把偏移与长度复制一份
        final long offset = start;
        final long length0 = length;
        StreamingResponseBody body = out -> {
            try (InputStream in = Files.newInputStream(file)) {
                in.skipNBytes(offset);
                byte[] buffer = new byte[BUFFER];
                long remaining = length0;
                while (remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        break;
                    }
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        };

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_TYPE, type.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
                .contentLength(length);
        if (partial) {
            builder.header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        }
        return builder.body(body);
    }

    /** 缩略图：首次访问时用 ffmpeg 抽一帧并缓存，之后直接读缓存 */
    @GetMapping("/api/media/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable long id,
                                            @RequestParam(name = "at", defaultValue = "1") double atSeconds) {
        MediaAsset asset = requireAsset(id);
        Path file = existingSource(asset);
        double at = Math.max(0, Math.min(atSeconds, Math.max(0, asset.durationMs() / 1000.0 - 0.1)));
        Path cache = storage.mediaDir(id).resolve("thumb-" + Math.round(at * 10) + ".jpg");
        if (!Files.isRegularFile(cache)) {
            boolean ok = renderFrame(file, at, cache, 480);
            if (!ok) {
                throw ApiException.notFound("无法生成缩略图，请确认本地已安装 ffmpeg");
            }
        }
        return image(cache);
    }

    /** 证据帧：按任务 + 序号取关键帧原图，前端用在时间轴卡片上 */
    @GetMapping("/api/jobs/{jobId}/frames/{seq}")
    public ResponseEntity<byte[]> evidenceFrame(@PathVariable long jobId, @PathVariable int seq) {
        List<VisualEvidence> items = visualRepository.listByJob(jobId);
        if (seq < 0 || seq >= items.size()) {
            throw ApiException.notFound("证据帧不存在: " + seq);
        }
        VisualEvidence evidence = items.get(seq);
        if (Texts.isBlank(evidence.framePath())) {
            throw ApiException.notFound("该条证据没有画面帧");
        }
        Path path = Path.of(evidence.framePath());
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("证据帧文件已被清理，请重新运行分析");
        }
        return image(path);
    }

    /** 按绝对时间取一帧（不落库），用于时间轴拖动预览 */
    @GetMapping("/api/media/{id}/frame")
    public ResponseEntity<byte[]> frameAt(@PathVariable long id,
                                          @RequestParam(name = "t", defaultValue = "0") double seconds,
                                          @RequestParam(name = "w", defaultValue = "640") int width) {
        MediaAsset asset = requireAsset(id);
        Path file = existingSource(asset);
        Path cache = storage.mediaDir(id).resolve("preview-" + Math.round(seconds * 10) + "-" + width + ".jpg");
        if (!Files.isRegularFile(cache)) {
            if (!renderFrame(file, seconds, cache, width)) {
                throw ApiException.notFound("无法抽取该时间点的画面");
            }
        }
        return image(cache);
    }

    private boolean renderFrame(Path source, double seconds, Path target, int width) {
        storage.ensure(target.getParent());
        List<String> cmd = List.of(
                props.getMedia().getFfmpeg(), "-hide_banner", "-nostdin", "-y",
                "-ss", String.format(java.util.Locale.ROOT, "%.2f", seconds),
                "-i", source.toString(),
                "-frames:v", "1",
                "-vf", "scale='min(" + width + "\\,iw)':-2",
                "-q:v", "4",
                target.toString());
        ProcessRunner.Result result = runner.run(cmd, Duration.ofSeconds(60), null, null, null, null);
        if (!result.ok()) {
            log.warn("抽帧失败 {} @{}s: {}", source.getFileName(), seconds, result.failure());
            return false;
        }
        return Files.isRegularFile(target);
    }

    private ResponseEntity<byte[]> image(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .body(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("读取图片失败: " + path, e);
        }
    }

    private MediaAsset requireAsset(long id) {
        return mediaRepository.find(id).orElseThrow(() -> ApiException.notFound("媒体不存在: " + id));
    }

    private Path existingSource(MediaAsset asset) {
        if (Texts.isBlank(asset.storagePath())) {
            throw ApiException.notFound("该媒体尚未落盘，请重新上传");
        }
        Path path = Path.of(asset.storagePath());
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("原始文件不存在，可能已被清理");
        }
        return path;
    }

    private long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取文件大小: " + file, e);
        }
    }

    /** 只支持单区间；解析失败返回 null，交给上层回 416 */
    private long[] parseRange(String header, long total) {
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0 || total <= 0) {
            return null;
        }
        try {
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(left);
                end = right.isEmpty() ? total - 1 : Math.min(Long.parseLong(right), total - 1);
            }
            if (start > end || start >= total) {
                return null;
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 兼容部分浏览器对 video 的预检请求 */
    @GetMapping("/api/media/{id}/info")
    public Map<String, Object> info(@PathVariable long id) {
        MediaAsset asset = requireAsset(id);
        return Map.of(
                "id", asset.id(),
                "name", asset.name(),
                "kind", asset.kind().name(),
                "durationMs", asset.durationMs(),
                "sizeBytes", asset.sizeBytes(),
                "playable", !Texts.isBlank(asset.storagePath()) && Files.isRegularFile(Path.of(asset.storagePath())),
                "stream", "/api/media/" + asset.id() + "/stream");
    }

    /** 保留给需要直接下载原片的场景 */
    @GetMapping("/api/media/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable long id) {
        MediaAsset asset = requireAsset(id);
        Path file = existingSource(asset);
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(asset.name(),
                                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
