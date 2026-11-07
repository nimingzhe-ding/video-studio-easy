package com.videostudio.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.videostudio.web.ApiExceptionHandler;
import com.videostudio.repository.JobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SafeErrorsTest {
    @ParameterizedTest @ValueSource(strings={
        "Authorization: Bearer secret-value-123",
        "Authorization: Basic secret-value-123",
        "{\"api_key\":\"secret-value-123\"}",
        "X-API-Key: secret-value-123",
        "password='secret-value-123 with spaces'",
        "https://user:secret-value-123@host/path",
        "https://host/path?access_token=secret-value-123&normal=yes",
        "https://host/path?X-Amz-Signature=secret-value-123",
        "client_secret=secret-value-123",
        "refresh-token: secret-value-123"
    }) void removesCredentialsFromCommonErrorFormats(String text) {
        String safe=SafeErrors.redact(text);
        assertFalse(safe.contains("secret-value-123"),safe);
        assertTrue(safe.contains("[REDACTED]"),safe);
    }
    @Test void redactsBeforeTruncationAndPreservesUsefulContext() {
        String safe=SafeErrors.message("HTTP 401 api_key=sk-sensitive-key-123 rejected\nretry disabled",42);
        assertFalse(safe.contains("sensitive"));
        assertTrue(safe.startsWith("HTTP 401 api_key=[REDACTED]"),safe);
        assertTrue(safe.length()<=42);
        assertEquals("HTTP 503 服务暂不可用",SafeErrors.message("HTTP 503\n服务暂不可用",100));
    }
    @Test void retainsUpstreamVendorAndQuerySecretProtection() {
        String safe=SafeErrors.redact("AWS AKIAIOSFODNN7EXAMPLE secret=upstream-secret-123 https://host/path?key=query-secret-456&sig=query-signature-789");
        assertFalse(safe.contains("AKIAIOSFODNN7EXAMPLE"));
        assertFalse(safe.contains("upstream-secret-123"));
        assertFalse(safe.contains("query-secret-456"));
        assertFalse(safe.contains("query-signature-789"));
    }
    @Test void apiExceptionsNeverReturnCredentialsToClients() {
        var response=new ApiExceptionHandler().handle(new ApiException(502,"Bearer secret-value-123"));
        assertEquals(502,response.getStatusCode().value());
        assertFalse(Json.write(response.getBody()).contains("secret-value-123"));
    }
    @Test void taskFailureIsRedactedAtThePersistenceBoundary() {
        var jdbc=mock(JdbcTemplate.class);
        new JobRepository(jdbc,mock(com.videostudio.security.CurrentUser.class)).markFailed(7,"HTTP 401 Bearer secret-value-123");
        verify(jdbc).update(contains("UPDATE analysis_job"),anyString(),anyString(),eq("HTTP 401 Bearer [REDACTED]"),any(),any(),eq(7L));
    }
}
