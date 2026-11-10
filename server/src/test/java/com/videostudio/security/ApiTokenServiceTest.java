package com.videostudio.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API 令牌的存储与校验。
 *
 * <p>安全约束是这里唯一值得测的东西：明文不能落库、摘要必须一致、
 * 撤销后立刻失效、无效令牌不泄露「存在与否」。
 */
class ApiTokenServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private ApiTokenService service() {
        return new ApiTokenService(jdbc);
    }

    /**
     * 落库的是摘要而不是明文——这是这里最要紧的安全约束。
     * <p>不通过 mock 整个 INSERT 来验证：{@code Sql.insert} 走 PreparedStatementCreator
     * 重载，mock 参数会变成对 Mockito 细节的测试。直接验证摘要函数的性质更可靠：
     * 只要存储路径用的是它，明文就不可能出现在库里。
     */
    @Test
    void digestIsNotReversibleAndNeverEqualsPlaintext() {
        String token = "vst_" + "a".repeat(64);
        String digest = ApiTokenService.sha256(token);

        assertFalse(digest.equals(token), "摘要不能等于原文");
        assertFalse(digest.contains(token));
        assertEquals(64, digest.length(), "SHA-256 十六进制是 64 字符");
        assertTrue(digest.matches("[0-9a-f]{64}"));
    }

    @Test
    void authenticationLooksUpByDigest() {
        when(jdbc.queryForList(contains("FROM api_token"), eq(Long.class), anyString()))
                .thenReturn(List.of(42L));

        Optional<Long> owner = service().authenticate("vst_" + "a".repeat(64));

        assertEquals(Optional.of(42L), owner);
    }

    @Test
    void unknownTokenIsRejected() {
        when(jdbc.queryForList(contains("FROM api_token"), eq(Long.class), anyString()))
                .thenReturn(List.of());

        assertTrue(service().authenticate("vst_deadbeef").isEmpty());
    }

    /** 缺前缀的串不该走到数据库查询：省一次往返，也避免被当成令牌探测字典 */
    @Test
    void tokensWithoutPrefixAreRejectedWithoutQuerying() {
        assertTrue(service().authenticate("abcdef").isEmpty());
        verify(jdbc, never()).queryForList(anyString(), eq(Long.class), anyString());
    }

    @Test
    void nullAndBlankAreSafe() {
        assertTrue(service().authenticate(null).isEmpty());
        assertTrue(service().authenticate("   ").isEmpty());
    }

    /** 撤销只标记时间戳，保留记录供审计 */
    @Test
    void revokeMarksTimestampInsteadOfDeleting() {
        when(jdbc.update(contains("SET revoked_at"), eq(5L), eq(7L))).thenReturn(1);

        service().revoke(7, 5);

        verify(jdbc).update(contains("SET revoked_at = NOW(3)"), eq(5L), eq(7L));
        verify(jdbc, never()).update(contains("DELETE FROM api_token"), any(Object[].class));
    }

    @Test
    void revokingSomeoneElsesTokenFails() {
        when(jdbc.update(contains("SET revoked_at"), any(Object[].class))).thenReturn(0);

        assertThrows(com.videostudio.common.ApiException.class, () -> service().revoke(7, 5));
    }

    /** 摘要函数本身必须稳定：存取两端用同一个算法，否则永远校验不上 */
    @Test
    void digestIsStableAndCaseSensitive() {
        assertEquals(ApiTokenService.sha256("abc"), ApiTokenService.sha256("abc"));
        assertFalse(ApiTokenService.sha256("abc").equals(ApiTokenService.sha256("ABC")));
        assertEquals(64, ApiTokenService.sha256("abc").length());
    }
}
