package com.videostudio.webhook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Webhook 签名。
 *
 * <p>接收方靠这个签名确认请求来自本服务。签名算法一旦与服务端不一致，
 * 所有接收方都会验签失败；而如果签名对内容不敏感，攻击者改几个字节也能通过。
 */
class WebhookServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    /** 同样的输入必须给出同样的签名，接收方才能复现 */
    @Test
    void signatureIsDeterministic() {
        String body = "{\"event\":\"job.succeeded\",\"jobId\":1}";

        assertEquals(WebhookService.sign(SECRET, "1700000000." + body),
                WebhookService.sign(SECRET, "1700000000." + body));
    }

    /** 正文改一个字符，签名必须完全不同——否则篡改无法被发现 */
    @Test
    void anyBodyChangeInvalidatesTheSignature() {
        String original = WebhookService.sign(SECRET, "1700000000.{\"jobId\":1}");
        String tampered = WebhookService.sign(SECRET, "1700000000.{\"jobId\":2}");

        assertNotEquals(original, tampered);
    }

    /** 时间戳参与签名，防止旧请求被重放 */
    @Test
    void timestampIsPartOfTheSignature() {
        String body = "{\"jobId\":1}";

        assertNotEquals(WebhookService.sign(SECRET, "1700000000." + body),
                WebhookService.sign(SECRET, "1700000001." + body));
    }

    /** 不同密钥给出不同签名，密钥不会退化 */
    @Test
    void differentSecretsProduceDifferentSignatures() {
        String payload = "1700000000.{\"jobId\":1}";

        assertNotEquals(WebhookService.sign(SECRET, payload),
                WebhookService.sign("ffffffffffffffffffffffffffffffff", payload));
    }

    /** 签名是 64 位十六进制的 HMAC-SHA256 */
    @Test
    void signatureShapeIsHmacSha256() {
        String signature = WebhookService.sign(SECRET, "payload");

        assertFalse(signature.isBlank());
        assertEquals(64, signature.length());
        assertEquals(true, signature.matches("[0-9a-f]{64}"));
    }
}
