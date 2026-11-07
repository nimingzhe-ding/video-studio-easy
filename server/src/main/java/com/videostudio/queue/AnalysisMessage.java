package com.videostudio.queue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.videostudio.common.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The queue contract is stricter than Jackson's default numeric/string coercion. */
record AnalysisMessage(long jobId, int generation) {
    private static final ObjectReader READER = Json.mapper().reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    static AnalysisMessage parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > 65_536)
            throw new IllegalArgumentException("消息体为空或超过大小限制");
        try {
            var root = READER.readTree(body);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("消息体必须为 JSON 对象");
            var job = root.path("jobId");
            var generation = root.path("generation");
            if (!job.isIntegralNumber() || !job.canConvertToLong() || job.longValue() <= 0
                || !generation.isIntegralNumber() || !generation.canConvertToInt() || generation.intValue() <= 0)
                throw new IllegalArgumentException("jobId 与 generation 必须为有效的正整数");
            return new AnalysisMessage(job.longValue(), generation.intValue());
        } catch (IOException ex) {
            // Parser exceptions can contain the original body. Never pass them to logs or persistence.
            throw new IllegalArgumentException("消息不是合法的单个 JSON 对象");
        }
    }

    static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 不可用", ex); }
    }

    static String identity(String brokerId, byte[] body) {
        if (brokerId == null || brokerId.isBlank()) return "body:" + digest(body);
        if (brokerId.length() <= 128 && brokerId.matches("[A-Za-z0-9_.:-]+")) return brokerId;
        return "broker:" + digest(brokerId.getBytes(StandardCharsets.UTF_8));
    }
}
