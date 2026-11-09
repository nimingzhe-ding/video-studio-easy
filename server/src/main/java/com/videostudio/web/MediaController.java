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
import org.springframework.web.bind.annotation.RequestBody;
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
        // 上传按账户限流：这里还没有素材 id 可用，而上传是最容易被脚本批量刷的入口
        limiter.require("rate:upload:user:" + request.getUserPrincipal().getName(), rateLimits.getUploadPerMinute(),
                java.time.Duration.ofMinutes(1), "上传过于频繁");
        return ApiResponse.success(ingestService.ingest(file));
    }

    /**
     * 从网页链接导入视频（B 站、YouTube 等，由 yt-dlp 支持）。
     *
     * <p>下载在请求线程里同步完成：一条 10 分钟的视频通常几十秒到几分钟，
     * 比让前端轮询一个异步任务简单。上传与链接导入共用同一套限流与入库逻辑，
     * 因此限流键保持按账户计算。
     */
    @PostMapping("/from-url")
    public ApiResponse<MediaAsset> importUrl(@RequestBody UrlRequest body,
                                             jakarta.servlet.http.HttpServletRequest request) {
        limiter.require("rate:upload:user:" + request.getUserPrincipal().getName(),
                rateLimits.getUploadPerMinute(), java.time.Duration.ofMinutes(1), "导入过于频繁");
        if (body == null || body.url() == null || body.url().isBlank()) {
            throw new ApiException("请提供视频链接");
        }
        return ApiResponse.success(ingestService.ingestUrl(body.url()));
    }

    public record UrlRequest(String url) {
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
        return ApiResponse.success(jobRepository.listByAssetForOwner(id));
    }

    /** 删除媒体：连带其任务、证据、报告与磁盘目录，避免留下孤儿数据 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        cleanupService.deleteMedia(id);
        return ApiResponse.success();
    }
}
