package com.videostudio.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从 {@code Authorization: Bearer <token>} 解析 API 令牌。
 *
 * <p>关键是认证成功后把 principal 设为<b>用户 id 字符串</b>，与浏览器会话一致。
 * 这样 {@link CurrentUser}、{@link OwnerGuard}、仓储层的归属过滤、
 * 按用户的限流与预算全部原样生效，不需要为 API 调用开一条旁路——
 * 否则每加一个入口都要重新检查一遍权限，漏一处就是越权。
 *
 * <p>只认 Bearer，不认 Cookie：带 API 令牌的请求如果同时带着浏览器会话，
 * 应当以令牌为准，避免「以为在用 A 身份，实际用了 B 身份」。
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiTokenService tokens;

    public ApiTokenFilter(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String raw = header.substring(BEARER.length()).trim();
            tokens.authenticate(raw).ifPresent(owner -> {
                var auth = UsernamePasswordAuthenticationToken.authenticated(
                        Long.toString(owner), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
            // 令牌无效时不在这里抛错：交给后续的鉴权链决定是 401 还是放行公开端点
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 令牌身份是每请求临时的，不能泄漏给线程池里的下一个请求
            SecurityContextHolder.clearContext();
        }
    }
}
