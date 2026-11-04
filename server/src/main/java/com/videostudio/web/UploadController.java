package com.videostudio.web;

import com.videostudio.common.ApiException;
import com.videostudio.common.ApiResponse;
import com.videostudio.domain.MediaAsset;
import com.videostudio.domain.UploadSession;
import com.videostudio.media.ChunkedUploadService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 分片上传接口。
 * <p>调用顺序：<code>POST /api/uploads</code> 创建会话 → 逐片
 * <code>PUT /api/uploads/{id}/chunks/{index}</code> → <code>POST /api/uploads/{id}/complete</code>。
 * 中断后重新创建同一 uploadId 会拿到已收分片列表，跳过即可续传。
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final ChunkedUploadService uploadService;

    public UploadController(ChunkedUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /** 创建会话的请求体。uploadId 由前端生成（通常是随机串），用于断点续传时定位同一个会话 */
    public record InitRequest(String uploadId, String name, long totalBytes) {
    }

    /** 会话状态，含已收到与缺失的分片序号 */
    public record SessionView(String uploadId, String name, long totalBytes, int totalChunks, int chunkSize,
                              int received, long receivedBytes, int percent, boolean finished, Long assetId,
                              List<Integer> receivedIndexes, List<Integer> missingIndexes) {

        static SessionView of(UploadSession session, long receivedBytes, List<Integer> received, List<Integer> missing) {
            int percent = session.totalBytes() <= 0 ? (session.finished() ? 100 : 0)
                    : (int) Math.min(100, Math.round(receivedBytes * 100.0 / session.totalBytes()));
            return new SessionView(session.uploadId(), session.name(), session.totalBytes(),
                    session.totalChunks(), session.chunkSize(), session.received(), receivedBytes,
                    percent, session.finished(), session.assetId(), received, missing);
        }
    }

    @PostMapping
    public ApiResponse<SessionView> init(@RequestBody InitRequest request) {
        if (request == null || request.uploadId() == null || request.uploadId().isBlank()) {
            throw new ApiException("缺少 uploadId");
        }
        UploadSession session = uploadService.init(request.uploadId(), request.name(), request.totalBytes());
        return ApiResponse.success(view(session));
    }

    @PutMapping("/{uploadId}/chunks/{index}")
    public ApiResponse<SessionView> uploadChunk(@PathVariable String uploadId,
                                                @PathVariable int index,
                                                @RequestParam("file") MultipartFile file) {
        UploadSession session = uploadService.writeChunk(uploadId, index, file);
        return ApiResponse.success(view(session));
    }

    @PostMapping("/{uploadId}/complete")
    public ApiResponse<Map<String, Object>> complete(@PathVariable String uploadId) {
        ChunkedUploadService.Merged merged = uploadService.complete(uploadId);
        MediaAsset asset = merged.asset();
        return ApiResponse.success(Map.of(
                "reused", merged.reused(),
                "media", asset,
                "assetId", asset.id()));
    }

    @GetMapping("/{uploadId}")
    public ApiResponse<SessionView> status(@PathVariable String uploadId) {
        UploadSession session = uploadService.status(uploadId)
                .orElseThrow(() -> ApiException.notFound("上传会话不存在或已过期: " + uploadId));
        return ApiResponse.success(view(session));
    }

    @DeleteMapping("/{uploadId}")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable String uploadId) {
        uploadService.cancel(uploadId);
        return ApiResponse.success(Map.of("canceled", true));
    }

    /** 当前生效的分片大小，前端据此切片（服务端可配置，避免前后端写死不一致） */
    @GetMapping("/policy")
    public ApiResponse<Map<String, Object>> policy() {
        return ApiResponse.success(Map.of("chunkSize", uploadService.chunkSize()));
    }

    private SessionView view(UploadSession session) {
        return SessionView.of(session, uploadService.receivedBytes(session),
                uploadService.receivedIndexes(session), uploadService.missingIndexes(session));
    }
}
