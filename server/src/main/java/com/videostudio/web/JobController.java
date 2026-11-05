package com.videostudio.web;

import com.videostudio.common.ApiException;
import com.videostudio.common.ApiResponse;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.config.RateLimitProperties;
import com.videostudio.infra.RateLimiter;
import com.videostudio.service.CleanupService;
import com.videostudio.service.JobService;
import com.videostudio.repository.JobEventRepository;
import com.videostudio.repository.JobRepository;
import com.videostudio.repository.ReportRepository;
import com.videostudio.repository.TranscriptRepository;
import com.videostudio.repository.VisualRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分析任务接口：提交、查询、取消、事件回放、证据与报告 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final JobRepository jobRepository;
    private final JobEventRepository eventRepository;
    private final ReportRepository reportRepository;
    private final TranscriptRepository transcriptRepository;
    private final VisualRepository visualRepository;
    private final CleanupService cleanupService;
    private final RateLimiter limiter;
    private final RateLimitProperties rateLimits;

    public JobController(JobService jobService, JobRepository jobRepository, JobEventRepository eventRepository,
                         ReportRepository reportRepository, TranscriptRepository transcriptRepository,
                         VisualRepository visualRepository, CleanupService cleanupService,
                         RateLimiter limiter, RateLimitProperties rateLimits) {
        this.cleanupService = cleanupService;
        this.jobService = jobService;
        this.jobRepository = jobRepository;
        this.eventRepository = eventRepository;
        this.reportRepository = reportRepository;
        this.transcriptRepository = transcriptRepository;
        this.visualRepository = visualRepository;
        this.limiter = limiter;
        this.rateLimits = rateLimits;
    }

    public record SubmitRequest(long assetId, String goal, String mode, String title) {
    }

    @PostMapping
    public ApiResponse<AnalysisJob> submit(@RequestBody SubmitRequest request) {
        if (request.assetId() <= 0) {
            throw new ApiException("缺少 assetId");
        }
        // 按素材限流而不是按 IP：同一用户可能换网络，但同一素材被反复提交一定是异常行为
        limiter.require("rate:submit:" + request.assetId(), rateLimits.getSubmitPerMinute(),
                java.time.Duration.ofMinutes(1), "该素材提交分析过于频繁");
        AnalysisMode mode = parseMode(request.mode());
        return ApiResponse.success(jobService.submit(request.assetId(), mode, request.goal(), request.title()));
    }

    /**
     * 模式解析：空值或 AUTO 返回 null，交给 {@link com.videostudio.service.ModeRouter} 判定；
     * 其余按枚举名严格匹配，非法值直接报错而不是静默变成「通用理解」。
     */
    private static AnalysisMode parseMode(String raw) {
        if (raw == null || raw.isBlank() || "AUTO".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        String value = raw.strip().toUpperCase();
        for (AnalysisMode mode : AnalysisMode.values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        // AnalysisMode.of() 会把非法值静默吞成 GENERAL，这里必须显式报错，
        // 否则客户端拼错模式名时会得到一份「看起来正常」但其实用错模板的报告
        throw new ApiException("不支持的分析模式: " + raw);
    }

    @GetMapping
    public ApiResponse<List<AnalysisJob>> list(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(jobRepository.list(Math.max(1, Math.min(200, limit))));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        AnalysisJob job = jobRepository.find(id).orElseThrow(() -> ApiException.notFound("任务不存在: " + id));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", job);
        body.put("report", reportRepository.findByJob(id).orElse(null));
        body.put("events", eventRepository.list(id, 0, 200));
        return ApiResponse.success(body);
    }

    @GetMapping("/{id}/events")
    public ApiResponse<?> events(@PathVariable long id, @RequestParam(defaultValue = "0") long after) {
        return ApiResponse.success(eventRepository.list(id, after, 500));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<?> report(@PathVariable long id) {
        return ApiResponse.success(reportRepository.findByJob(id)
                .orElseThrow(() -> ApiException.notFound("该任务还没有生成报告")));
    }

    @GetMapping("/{id}/transcript")
    public ApiResponse<?> transcript(@PathVariable long id, @RequestParam(defaultValue = "0") int from,
                                     @RequestParam(defaultValue = "2000") int size) {
        List<?> all = transcriptRepository.listByJob(id);
        int start = Math.max(0, Math.min(from, all.size()));
        int end = Math.min(all.size(), start + Math.max(1, size));
        return ApiResponse.success(Map.of("total", all.size(), "items", all.subList(start, end)));
    }

    @GetMapping("/{id}/evidence")
    public ApiResponse<?> evidence(@PathVariable long id) {
        return ApiResponse.success(visualRepository.listByJob(id));
    }

    /** 失败重试：接着原任务走，已完成的转写与抽帧会被复用 */
    @PostMapping("/{id}/retry")
    public ApiResponse<AnalysisJob> retry(@PathVariable long id) {
        return ApiResponse.success(jobService.retry(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable long id) {
        boolean canceled = jobService.cancel(id);
        return ApiResponse.success(Map.of("canceled", canceled));
    }

    public record ReanalyzeRequest(String goal, String mode) {
    }

    @PostMapping("/{id}/reanalyze")
    public ApiResponse<AnalysisJob> reanalyze(@PathVariable long id, @RequestBody(required = false) ReanalyzeRequest body) {
        String goal = body == null ? null : body.goal();
        AnalysisMode mode = body == null ? null : parseMode(body.mode());
        return ApiResponse.success(jobService.reanalyze(id, goal, mode));
    }

    /** 删除任务：运行中的先取消，再清证据、事件、报告与任务目录 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        cleanupService.deleteJob(id);
        return ApiResponse.success();
    }
}
