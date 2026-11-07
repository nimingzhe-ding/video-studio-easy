package com.videostudio.common;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 错误信息脱敏：把可能出现在异常文本里的凭证抹掉。
 *
 * <p>为什么需要：任务失败时 {@code e.getMessage()} 会原样写进 {@code analysis_job.error}
 * 并通过接口、SSE 推给前端。上游模型服务返回 401/403 时，异常文本里常常带着
 * 请求头原文（{@code Bearer sk-xxxx}）；HTTP 客户端的连接错误也可能回显 URL 上的
 * {@code api_key=...}。这类文本一旦入库，任何能看到任务详情的人都能拿到凭证。
 *
 * <p>原则是「宁可多抹」：抹掉的只是一段无法辨认的密钥，而漏掉的会是一次真实泄露。
 * 但同时要保证可读性——保留「什么形式的凭证、大致多长」，方便判断是哪个服务的问题。
 */
public final class Secrets {

    private Secrets() {
    }

    /**
     * 需要整体替换的片段。
     * <p>每条都是「前缀分组 + 凭证」的形状：替换时保留 $1、把后半截换成 ****，
     * 这样错误信息里还能看出「是什么形式的凭证」，而不是整句变成星号。
     * <p>注意每条都必须有捕获组，否则替换会抛 IndexOutOfBounds。
     */
    private static final List<Pattern> PATTERNS = List.of(
            // Authorization: Bearer xxx
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=\\-]{8,}"),
            // api_key=xxx / api-key: xxx / token=xxx / secret=xxx / password=xxx
            Pattern.compile("(?i)((?:api[-_ ]?key|access[-_ ]?key|token|secret|password)\\s*[=:]\\s*)[^\\s,;\"'&]{6,}"),
            // 常见厂商的密钥前缀
            Pattern.compile("\\b(sk-)[A-Za-z0-9_\\-]{16,}"),
            Pattern.compile("\\b(AKIA)[0-9A-Z]{16}\\b"),
            // URL 查询参数里的签名与密钥
            Pattern.compile("(?i)([?&](?:key|api_key|apikey|token|signature|sig|x-amz-credential|x-amz-signature)=)[^&\\s]{6,}"),
            // JSON 里的同名字段：{"apiKey":"xxx"}
            Pattern.compile("(?i)(\"(?:api[-_]?key|access[-_]?key|token|secret|password)\"\\s*:\\s*\")[^\"]{6,}"));
    private static final String MASK = "****";

    /**
     * 抹掉凭证，其余内容原样保留。
     *
     * @param text 原始文本，null 安全
     * @return 已脱敏的文本；不包含可识别凭证时与原文本相同
     */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Pattern pattern : PATTERNS) {
            out = pattern.matcher(out).replaceAll("$1" + MASK);
        }
        return out;
    }

    /**
     * 脱敏后再压成单行并截断，用于写库与推给前端。
     * <p>顺序不能反过来：先截断再脱敏，可能把凭证的后半截留下、前半截截掉，
     * 正则匹配不上，等于没脱敏。
     */
    public static String scrubOneLine(String text, int maxLength) {
        return Texts.oneLine(scrub(text), maxLength);
    }
}
