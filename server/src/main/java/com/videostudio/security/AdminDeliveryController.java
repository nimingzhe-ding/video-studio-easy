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
 * 管理端运维接口：失败投递的查询与重投。
 *
 * <p>由 {@link SecurityConfig} 的 {@code /api/admin/** -> hasRole("ADMIN")} 保护。
 * 只提供「看」和「重新排队」两个动作，不提供「删死信」——删除会让 Broker 里的原始消息
 * 变成无人认领的孤儿，重投到处理栈里才是正确的恢复路径。
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
}
