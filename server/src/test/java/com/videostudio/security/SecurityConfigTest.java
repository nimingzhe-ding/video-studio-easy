package com.videostudio.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSRF 豁免的判定：只有显式携带 Bearer 的请求才跳过 CSRF。 */
class SecurityConfigTest {

    private boolean bearer(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/jobs");
        if (header != null) {
            request.addHeader("Authorization", header);
        }
        return SecurityConfig.hasBearerToken(request);
    }

    @Test
    void bearerTokenIsExempt() {
        assertTrue(bearer("Bearer vst_abc123"));
    }

    @Test
    void schemeMatchIsCaseInsensitive() {
        // RFC 7235 规定认证方案大小写不敏感
        assertTrue(bearer("bearer vst_abc123"));
    }

    @Test
    void browserSessionStillRequiresCsrf() {
        assertFalse(bearer(null));
        assertFalse(bearer(""));
        assertFalse(bearer("Basic dXNlcjpwYXNz"));
    }
}
