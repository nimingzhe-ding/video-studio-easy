package com.videostudio.web;

import com.videostudio.common.ApiException;
import com.videostudio.common.ApiResponse;
import com.videostudio.config.RateLimitProperties;
import com.videostudio.infra.RateLimiter;
import com.videostudio.domain.MediaAsset;
import com.videostudio.media.MediaIngestService;
import com.videostudio.repository.JobRepository;
import com.videostudio.repository.MediaRepository;
import com.videostudio.service.CleanupService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 媒体资产接口：上传、列表、详情 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaIngestService ingestService;
    private final MediaRepository mediaRepository;
    private final JobRepository jobRepository;
    private final CleanupService cleanupService;
    private final RateLimiter limiter;
    private final RateLimitProperties rateLimits;

    public MediaController(MediaIngestService ingestService, MediaRepository mediaRepository,
                           JobRepository jobRepository, CleanupService cleanupService,
                           RateLimiter limiter, RateLimitProperties rateLimits) {
        this.ingestService = ingestService;
        this.mediaRepository = mediaRepository;
        this.jobRepository = jobRepository;
        this.cleanupService = cleanupService;
        this.limiter = limiter;
        this.rateLimits = rateLimits;
    }

    @PostMapping
    public ApiResponse<MediaAsset> upload(@RequestParam("file") MultipartFile file,
                                          jakarta.servlet.http.HttpServletRequest request) {
        // 上传按客户端 IP 限流：这里还没有素材 id 可用，而上传是最容易被脚本批量刷的入口
        limiter.require("rate:upload:" + clientIp(request), rateLimits.getUploadPerMinute(),
                java.time.Duration.ofMinutes(1), "上传过于频繁");
        return ApiResponse.success(ingestService.ingest(file));
    }

    @GetMapping
    public ApiResponse<List<MediaAsset>> list(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(mediaRepository.list(Math.max(1, Math.min(200, limit))));
    }

    @GetMapping("/{id}")
    public ApiResponse<MediaAsset> detail(@PathVariable long id) {
        return ApiResponse.success(mediaRepository.find(id)
                .orElseThrow(() -> ApiException.notFound("媒体不存在: " + id)));
    }

    @GetMapping("/{id}/jobs")
    public ApiResponse<?> jobs(@PathVariable long id) {
        return ApiResponse.success(jobRepository.listByAsset(id));
    }

    /** 删除媒体：连带其任务、证据、报告与磁盘目录，避免留下孤儿数据 */
    /** 取真实客户端 IP：经过反向代理时要读 X-Forwarded-For 的第一个 */
    static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).strip();
        }
        String real = request.getHeader("X-Real-IP");
        return real == null || real.isBlank() ? request.getRemoteAddr() : real.strip();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        cleanupService.deleteMedia(id);
        return ApiResponse.success();
    }
}
