package com.videostudio.security;

import com.videostudio.common.ApiResponse;
import com.videostudio.common.Json;
import com.videostudio.webhook.WebhookService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 外部集成：API 令牌与 Webhook 订阅。
 *
 * <p>放在 {@code /api/integrations} 下，任何已登录用户都能管理自己的一份；
 * 令牌的作用域就是它所属的账户，没有单独的权限粒度——一个用户能做的事，
 * 他的令牌都能做。这样不用维护第二套权限模型，代价是令牌泄露等同于账号泄露，
 * 因此支持按需撤销。
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

    private final ApiTokenService tokens;
    private final WebhookService webhooks;
    private final CurrentUser current;

    public IntegrationController(ApiTokenService tokens, WebhookService webhooks, CurrentUser current) {
        this.tokens = tokens;
        this.webhooks = webhooks;
        this.current = current;
    }

    public record NameRequest(String name) {
    }

    public record WebhookRequest(String url, String events) {
    }

    /**
     * 创建令牌。明文只在这一次响应里出现，之后服务端只存摘要。
     * 前端必须提示用户「只显示一次」。
     */
    @PostMapping("/tokens")
    public ApiResponse<Map<String, Object>> createToken(@RequestBody(required = false) NameRequest body) {
        long userId = current.id();
        ApiTokenService.Created created = tokens.create(userId, body == null ? null : body.name());
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", created.id());
        payload.put("name", created.name());
        payload.put("token", created.token());
        payload.put("createdAt", created.createdAt().toString());
        payload.put("notice", "令牌只显示这一次，请立即保存");
        return ApiResponse.success(payload);
    }

    @GetMapping("/tokens")
    public ApiResponse<?> listTokens() {
        return ApiResponse.success(tokens.list(current.id()));
    }

    @DeleteMapping("/tokens/{id}")
    public ApiResponse<Void> revokeToken(@PathVariable long id) {
        tokens.revoke(current.id(), id);
        return ApiResponse.success();
    }

    @PostMapping("/webhooks")
    public ApiResponse<Map<String, Object>> createWebhook(@RequestBody WebhookRequest body) {
        if (body == null || body.url() == null || body.url().isBlank()) {
            throw new com.videostudio.common.ApiException("请提供回调地址");
        }
        WebhookService.Created created = webhooks.create(current.id(), body.url(), body.events());
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", created.id());
        payload.put("url", created.url());
        payload.put("events", created.events());
        payload.put("secret", created.secret());
        payload.put("notice", "签名密钥只显示这一次，请保存到接收方用于验签");
        return ApiResponse.success(payload);
    }

    @GetMapping("/webhooks")
    public ApiResponse<List<WebhookService.Subscription>> listWebhooks() {
        return ApiResponse.success(webhooks.list(current.id()));
    }

    @DeleteMapping("/webhooks/{id}")
    public ApiResponse<Void> deleteWebhook(@PathVariable long id) {
        webhooks.delete(current.id(), id);
        return ApiResponse.success();
    }
}
