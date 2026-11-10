package com.videostudio.webhook;

import com.videostudio.common.Json;
import com.videostudio.common.Secrets;
import com.videostudio.common.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook 投递：任务到达终态时通知外部系统。
 *
 * <p>三个刻意的设计：
 * <ul>
 *   <li><b>异步</b>：回调在独立线程池里发，失败不影响任务本身。
 *       接收方挂了不该让分析失败。</li>
 *   <li><b>签名</b>：请求头带 {@code X-VS-Signature}，用 HMAC-SHA256 对
 *       「时间戳 + 正文」签名。接收方按同样方式验签，就能确认请求确实来自本服务，
 *       而不是任何人构造的 POST。</li>
 *   <li><b>不重试</b>：只发一次。重试需要持久化队列与幂等键，而 webhook 的语义
 *       本就是「尽力通知」——需要可靠送达的，应该轮询任务接口而不是依赖回调。</li>
 * </ul>
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String EVENT_SUCCEEDED = "job.succeeded";
    private static final String EVENT_FAILED = "job.failed";

    private final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "webhook-sender");
                t.setDaemon(true);
                return t;
            });

    public WebhookService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Subscription(long id, long userId, String url, String events, boolean enabled) {
    }

    /**
     * 创建订阅，返回 id 与签名密钥。
     *
     * <p>密钥只在这里返回一次，之后接口再也读不到——与 API 令牌同样的处理。
     * 接收方需要它来验签，因此创建时必须保存下来。
     */
    public record Created(long id, String url, String secret, String events) {
    }

    public Created create(long userId, String url, String events) {
        String target = url == null ? "" : url.strip();
        validateUrl(target);
        String eventsValue = events == null || events.isBlank()
                ? EVENT_SUCCEEDED + "," + EVENT_FAILED : events.strip();
        String secret = randomHex(24);
        long id = Sql.insert(jdbc, """
                INSERT INTO webhook (user_id, url, secret, events, enabled, created_at)
                VALUES (?,?,?,?,TRUE,NOW(3))
                """, userId, target, secret, eventsValue);
        log.info("已为用户 {} 创建 webhook id={} url={}", userId, id, target);
        return new Created(id, target, secret, eventsValue);
    }

    public List<Subscription> list(long userId) {
        return jdbc.query("""
                SELECT id, user_id, url, events, enabled FROM webhook
                WHERE user_id = ? ORDER BY id DESC
                """, (rs, i) -> new Subscription(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("url"),
                rs.getString("events"), rs.getBoolean("enabled")), userId);
    }

    public void delete(long userId, long id) {
        int updated = jdbc.update("DELETE FROM webhook WHERE id = ? AND user_id = ?", id, userId);
        if (updated != 1) {
            throw com.videostudio.common.ApiException.notFound("Webhook 不存在");
        }
    }

    /**
     * 通知某任务的所有订阅者。
     *
     * @param terminal 终态：SUCCEEDED / FAILED / CANCELED
     */
    public void notifyTerminal(long jobId, long userId, String terminal, String error) {
        if (userId <= 0) {
            return;
        }
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    SELECT id, url, secret, events FROM webhook
                    WHERE user_id = ? AND enabled = TRUE
                    """, userId);
        } catch (RuntimeException ex) {
            log.error("读取 webhook 配置失败 job={}", jobId, ex);
            return;
        }
        String event = "SUCCEEDED".equals(terminal) ? EVENT_SUCCEEDED : EVENT_FAILED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("jobId", jobId);
        payload.put("status", terminal);
        payload.put("error", error == null ? "" : Secrets.scrub(error));
        payload.put("sentAt", Instant.now().toString());
        String body = Json.write(payload);

        for (Map<String, Object> row : rows) {
            String events = (String) row.get("events");
            if (events != null && !events.contains(event)) {
                continue;
            }
            String url = (String) row.get("url");
            String secret = (String) row.get("secret");
            // 交给线程池：接收方响应慢不能拖住任务收尾
            pool.submit(() -> deliver(jobId, (String) url, secret, body));
        }
    }

    private void deliver(long jobId, String url, String secret, String body) {
        long timestamp = Instant.now().getEpochSecond();
        String signature = sign(secret, timestamp + "." + body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-VS-Signature", "t=" + timestamp + ",v1=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("webhook 返回非 2xx job={} status={}", jobId, response.statusCode());
            } else {
                log.debug("webhook 已送达 job={}", jobId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 接收方不可达是常态，记一行即可；不重试
            log.warn("webhook 投递失败 job={}: {}", jobId, Secrets.scrub(e.getMessage()));
        }
    }

    static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    /** 只接受公网 http/https，阻止把回调打进内网或云元数据端点 */
    private void validateUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new com.videostudio.common.ApiException("Webhook 地址格式不正确");
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new com.videostudio.common.ApiException("Webhook 地址必须是公网 http/https");
        }
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        new java.security.SecureRandom().nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
