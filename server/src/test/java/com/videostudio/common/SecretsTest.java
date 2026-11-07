package com.videostudio.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误信息脱敏。
 *
 * <p>这些用例都对应真实会发生的形状：上游模型 401 时把请求头回显进异常、
 * HTTP 客户端把 URL 上的 api_key 打进连接错误、S3 的签名参数出现在报错里。
 * 漏掉任何一种，凭证就会随任务详情一起暴露给任何能看到该任务的人。
 */
class SecretsTest {

    @Test
    void bearerTokenIsMasked() {
        String text = "401 Unauthorized: Authorization: Bearer sk-abcdefghijklmnopqrstuvwxyz";

        String scrubbed = Secrets.scrub(text);

        assertFalse(scrubbed.contains("sk-abcdefghijklmnopqrstuvwxyz"));
        assertTrue(scrubbed.contains("Bearer"), "保留凭证形式，便于判断是哪个服务的问题");
        assertTrue(scrubbed.contains("****"));
    }

    @Test
    void namedSecretParametersAreMasked() {
        for (String text : new String[]{
                "api_key=abcd1234efgh5678",
                "api-key: abcd1234efgh5678",
                "token=abcd1234efgh5678",
                "secret=abcd1234efgh5678",
                "password=abcd1234efgh5678",
                "access_key=abcd1234efgh5678"}) {
            String scrubbed = Secrets.scrub(text);
            assertFalse(scrubbed.contains("abcd1234efgh5678"), "未脱敏: " + text);
        }
    }

    @Test
    void vendorPrefixedKeysAreMasked() {
        assertFalse(Secrets.scrub("invalid key sk-proj-abcdefghijklmnopqrstuvwxyz123456")
                .contains("sk-proj-abcdefghijklmnopqrstuvwxyz123456"));
        assertFalse(Secrets.scrub("AWS error for AKIAIOSFODNN7EXAMPLE").contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void urlQuerySecretsAreMasked() {
        String scrubbed = Secrets.scrub(
                "GET https://bucket.s3.example.com/video.mp4?X-Amz-Credential=abc123&X-Amz-Signature=deadbeef failed");

        assertFalse(scrubbed.contains("abc123"));
        assertFalse(scrubbed.contains("deadbeef"));
        assertTrue(scrubbed.contains("video.mp4"), "URL 里非敏感的部分要保留，否则无法定位问题");
    }

    @Test
    void jsonFieldsAreMasked() {
        String scrubbed = Secrets.scrub("{\"model\":\"qwen\",\"apiKey\":\"abcd1234efgh5678\"}");

        assertFalse(scrubbed.contains("abcd1234efgh5678"));
        assertTrue(scrubbed.contains("qwen"), "普通字段不受影响");
    }

    @Test
    void ordinaryTextIsUnchanged() {
        String text = "模型返回的计划结构不合法；已尝试 3 次";
        assertEquals(text, Secrets.scrub(text));
    }

    @Test
    void shortValuesAreLeftAloneSoErrorMessagesStayReadable() {
        // 值太短不可能是凭证，强行抹掉只会让错误信息变得莫名其妙
        String text = "api_key=short";
        assertTrue(Secrets.scrub(text).contains("short"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals(null, Secrets.scrub(null));
        assertEquals("", Secrets.scrub(""));
    }

    /**
     * 顺序要求：先脱敏再截断。
     * <p>反过来会把凭证切成半截，正则就匹配不上了——这条用例守住的是调用顺序。
     */
    @Test
    void scrubBeforeTruncateKeepsSecretsIntact() {
        String longText = "x".repeat(250) + " api_key=abcd1234efgh5678";

        String out = Secrets.scrubOneLine(longText, 300);

        assertFalse(out.contains("abcd1234efgh5678"));
    }

    /** 截断后仍要压成单行，避免把换行带进数据库列与 JSON 输出 */
    @Test
    void resultIsSingleLineAndBounded() {
        String out = Secrets.scrubOneLine("第一行\n第二行\ttab", 300);

        assertFalse(out.contains("\n"));
        assertFalse(out.contains("\t"));
        assertTrue(out.length() <= 301, "含省略号时最多比上限多一个字符");
    }
}
