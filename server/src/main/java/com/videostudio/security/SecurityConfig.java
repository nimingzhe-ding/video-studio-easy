package com.videostudio.security;

import com.videostudio.common.ApiResponse;
import com.videostudio.common.Json;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
    @Bean CookieCsrfTokenRepository csrfTokenRepository() {
        var repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/");
        return repo;
    }
    @Bean UserDetailsService users(JdbcTemplate jdbc) {
        return username -> jdbc.query("SELECT id, password_hash, role FROM app_user WHERE username = ? AND enabled = TRUE",
            (rs, i) -> User.withUsername(Long.toString(rs.getLong("id")))
                .password(rs.getString("password_hash")).roles(rs.getString("role")).build(),
            username.strip().toLowerCase(java.util.Locale.ROOT)).stream().findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("账号或密码错误"));
    }
    @Bean SecurityFilterChain security(HttpSecurity http, CookieCsrfTokenRepository csrf,
                                     HttpSessionSecurityContextRepository contexts,
                                     ApiTokenFilter tokenFilter) throws Exception {
        // 令牌过滤器在匿名认证之前跑：带 Bearer 的请求以令牌身份为准，
        // 而不是「碰巧带着的浏览器会话」
        http.addFilterBefore(tokenFilter,
                org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
        return http.securityContext(c -> c.securityContextRepository(contexts))
            .csrf(c -> c.csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a -> a.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/**", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**", "/ws/**").authenticated().anyRequest().permitAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write(Json.write(ApiResponse.error("登录已过期，请重新登录")));
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write(Json.write(ApiResponse.error("没有访问权限或请求凭证已失效")));
                }))
            .logout(l -> l.logoutUrl("/api/auth/logout").invalidateHttpSession(true).deleteCookies("SESSION")
                .logoutSuccessHandler((req, res, auth) -> {
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write(Json.write(ApiResponse.success()));
                }))
            .build();
    }
}
