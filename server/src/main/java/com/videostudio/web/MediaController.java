package com.videostudio.web;

import com.videostudio.common.ApiException;
import com.videostudio.common.ApiResponse;
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

    public MediaController(MediaIngestService ingestService, MediaRepository mediaRepository,
                           JobRepository jobRepository, CleanupService cleanupService) {
        this.ingestService = ingestService;
        this.mediaRepository = mediaRepository;
        this.jobRepository = jobRepository;
        this.cleanupService = cleanupService;
    }

    @PostMapping
    public ApiResponse<MediaAsset> upload(@RequestParam("file") MultipartFile file) {
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
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        cleanupService.deleteMedia(id);
        return ApiResponse.success();
    }
}
