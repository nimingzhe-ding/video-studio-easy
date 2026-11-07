package com.videostudio.security;

import com.videostudio.common.ApiResponse;
import com.videostudio.service.FailedDeliveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端运维接口：失败任务重投与异常消息处理审计。
 *
 * <p>由 {@link SecurityConfig} 的 {@code /api/admin/** -> hasRole("ADMIN")} 保护。
 * 异常消息标记已处理后仍保留审计记录；普通失败任务通过递增代次重新排队。
 */
@RestController
@RequestMapping("/api/admin/failed-deliveries")
public class AdminDeliveryController {

    private final FailedDeliveryService deliveries;
    private final CurrentUser current;

    public AdminDeliveryController(FailedDeliveryService deliveries, CurrentUser current) {
        this.deliveries = deliveries;
        this.current = current;
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(defaultValue = "true") boolean pendingOnly,
                               @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(deliveries.list(pendingOnly, limit));
    }

    @PostMapping("/{id}/replay")
    public ApiResponse<Map<String, Object>> replay(@PathVariable long id) {
        long jobId = deliveries.replay(id, current.id());
        return ApiResponse.success(Map.of("replayed", true, "jobId", jobId));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<?> resolve(@PathVariable long id) {
        deliveries.resolve(id,current.id());
        return ApiResponse.success(Map.of("resolved",true));
    }
}
