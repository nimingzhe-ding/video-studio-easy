package com.videostudio.security;

import com.videostudio.common.ApiException;
import com.videostudio.common.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * API 令牌：供外部系统调用，不依赖浏览器会话。
 *
 * <p>存储用 SHA-256 摘要而不是明文：令牌只在一处出现——创建时返回给用户，
 * 之后服务端再也拿不到原文。拖库时无法还原，也就无法冒用。
 *
 * <p>为什么用 SHA-256 而不是 BCrypt：令牌是 32 字节高熵随机串，不是人选的密码，
 * 没有字典攻击面；而每次请求都要校验，BCrypt 的成本因子会让高频调用变慢。
 */
@Service
public class ApiTokenService {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenService.class);
    private static final String PREFIX = "vst_";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 令牌前缀的展示长度：够人工认出是哪个令牌，又不足以还原原文 */
    private static final int DISPLAY_PREFIX = 12;

    private final JdbcTemplate jdbc;

    public ApiTokenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建时返回明文，之后无从取回 */
    public record Created(long id, String name, String token, Instant createdAt) {
    }

    public record TokenView(long id, String name, String prefix, Instant createdAt,
                            Instant lastUsedAt, boolean revoked) {
    }

    public Created create(long userId, String name) {
        String label = name == null || name.isBlank() ? "未命名令牌" : name.strip();
        if (label.length() > 128) {
            throw new ApiException("令牌名称不能超过 128 字");
        }
        String raw = PREFIX + randomHex(32);
        String hash = sha256(raw);
        long id = Sql.insert(jdbc, """
                INSERT INTO api_token (user_id, name, prefix, token_hash, created_at)
                VALUES (?,?,?,?,NOW(3))
                """, userId, label, raw.substring(0, DISPLAY_PREFIX), hash);
        log.info("已为用户 {} 创建 API 令牌 id={} name={}", userId, id, label);
        return new Created(id, label, raw, Instant.now());
    }

    /** 当前用户的令牌列表；不返回摘要，只返回可用于识别的前缀 */
    public List<TokenView> list(long userId) {
        return jdbc.query("""
                SELECT id, name, prefix, created_at, last_used_at, revoked_at
                FROM api_token WHERE user_id = ? ORDER BY id DESC
                """, (rs, i) -> new TokenView(
                rs.getLong("id"), rs.getString("name"), rs.getString("prefix"),
                Sql.instant(rs.getTimestamp("created_at")),
                Sql.instant(rs.getTimestamp("last_used_at")),
                rs.getTimestamp("revoked_at") != null), userId);
    }

    /** 撤销：只标记时间戳，保留记录供审计 */
    public void revoke(long userId, long tokenId) {
        int updated = jdbc.update(
                "UPDATE api_token SET revoked_at = NOW(3) WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                tokenId, userId);
        if (updated != 1) {
            throw ApiException.notFound("令牌不存在或已撤销");
        }
    }

    /**
     * 校验令牌并返回所属用户。
     *
     * <p>空结果统一返回 empty，不区分「不存在」与「已撤销」——区分开等于告诉攻击者
     * 哪一串是真实存在过的令牌。
     */
    public Optional<Long> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String token = rawToken.strip();
        if (!token.startsWith(PREFIX)) {
            return Optional.empty();
        }
        List<Long> owners = jdbc.queryForList("""
                SELECT user_id FROM api_token
                WHERE token_hash = ? AND revoked_at IS NULL
                """, Long.class, sha256(token));
        if (owners.isEmpty()) {
            return Optional.empty();
        }
        long owner = owners.get(0);
        // 记录最近使用时间用于运维识别闲置令牌；失败不影响认证
        try {
            jdbc.update("""
                    UPDATE api_token SET last_used_at = NOW(3)
                    WHERE token_hash = ? AND (last_used_at IS NULL OR last_used_at < TIMESTAMPADD(MINUTE, -1, NOW(3)))
                    """, sha256(token));
        } catch (RuntimeException ex) {
            log.debug("更新令牌使用时间失败: {}", ex.getMessage());
        }
        return Optional.of(owner);
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
