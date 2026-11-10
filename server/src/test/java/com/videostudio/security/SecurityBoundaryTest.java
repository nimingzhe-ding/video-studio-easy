package com.videostudio.security;

import com.videostudio.common.ApiResponse;
import com.videostudio.infra.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(controllers={SecurityBoundaryTest.ProtectedReport.class,AuthController.class},
    properties={"auth.registration-enabled=false"})
@Import({SecurityConfig.class,CurrentUser.class,OwnerGuard.class,AuthController.class,
    SecurityBoundaryTest.ProtectedReport.class,com.videostudio.web.ApiExceptionHandler.class,
    SecurityBoundaryTest.OwnershipConfig.class})
class SecurityBoundaryTest {
    @Autowired MockMvc mvc;
    @MockBean JdbcTemplate jdbc;
    @MockBean RateLimiter limiter;
    /**
     * SecurityConfig 会注册令牌过滤器。切片测试里不能直接用 Mockito 替身——
     * 默认 mock 不会调用 chain.doFilter，请求会被吃掉、整个鉴权链失效。
     * 这里让它透传。
     */
    @MockBean ApiTokenFilter apiTokenFilter;

    @org.junit.jupiter.api.BeforeEach
    void passThroughTokenFilter() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            jakarta.servlet.ServletRequest req = invocation.getArgument(0);
            jakarta.servlet.ServletResponse res = invocation.getArgument(1);
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(apiTokenFilter).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
    @RestController static class ProtectedReport {
        @GetMapping("/api/jobs/{id}/report") ApiResponse<?> report(@PathVariable long id) { return ApiResponse.success("private"); }
    }
    @Configuration static class OwnershipConfig implements WebMvcConfigurer {
        private final OwnerGuard guard;
        OwnershipConfig(OwnerGuard guard) { this.guard=guard; }
        @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(guard); }
    }
    @Test void anonymousDownloadRequiresLogin() throws Exception { mvc.perform(get("/api/jobs/1/report")).andExpect(status().isUnauthorized()); }
    @Test void mutationRequiresCsrfToken() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"long-password\"}")).andExpect(status().isForbidden());
    }
    @Test void closedRegistrationCannotBeBypassed() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content("{\"username\":\"user\",\"password\":\"long-password\"}")).andExpect(status().isForbidden());
    }
    /**
     * 归属校验必须验证「判定结果」，而不只是「mock 返回了 null」。
     * <p>这里显式让 OwnerGuard 的归属查询返回 0 与 1：0 表示这条记录不属于当前用户，
     * 1 表示属于。两条断言合起来才说明「越权拿不到、有权限拿得到」；
     * 单看 404 那条只能证明 SQL 没抛异常。
     */
    @WithMockUser(username="7") @Test void anotherUsersReportReturns404() throws Exception {
        org.mockito.Mockito.when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("FROM analysis_job j JOIN media_asset m"),
                org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq(99L), org.mockito.ArgumentMatchers.eq(7L)))
            .thenReturn(0L);
        mvc.perform(get("/api/jobs/99/report")).andExpect(status().isNotFound());
    }

    @WithMockUser(username="7") @Test void ownReportIsServed() throws Exception {
        org.mockito.Mockito.when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("FROM analysis_job j JOIN media_asset m"),
                org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq(7L)))
            .thenReturn(1L);
        mvc.perform(get("/api/jobs/5/report")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value("private"));
    }
    @WithMockUser(username="7") @Test void normalUserCannotManageAccounts() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
    }
    @Test void successfulLoginPersistsAuthenticationForLaterRequests() throws Exception {
        var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        org.mockito.Mockito.when(jdbc.queryForList("SELECT id,password_hash,role FROM app_user WHERE username = ? AND enabled = TRUE","editor"))
            .thenReturn(java.util.List.of(java.util.Map.of("id",7L,"password_hash",encoder.encode("correct-password"),"role","USER")));
        org.mockito.Mockito.when(jdbc.queryForMap("SELECT id, username, role FROM app_user WHERE id = ? AND enabled = TRUE",7L))
            .thenReturn(java.util.Map.of("id",7L,"username","editor","role","USER"));
        var result=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"username\":\"editor\",\"password\":\"correct-password\"}")).andExpect(status().isOk()).andReturn();
        var session=(org.springframework.mock.web.MockHttpSession)result.getRequest().getSession(false);
        org.junit.jupiter.api.Assertions.assertNotNull(session);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("editor"));
    }
}
