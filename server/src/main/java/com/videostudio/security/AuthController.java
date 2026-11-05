package com.videostudio.security;

import com.videostudio.common.ApiException;
import com.videostudio.common.ApiResponse;
import com.videostudio.common.Sql;
import com.videostudio.infra.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final CurrentUser current;
    private final RateLimiter limiter;
    private final HttpSessionSecurityContextRepository contexts;
    private final CookieCsrfTokenRepository csrf;
    private final boolean registration;
    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwords, CurrentUser current, RateLimiter limiter,
            HttpSessionSecurityContextRepository contexts, CookieCsrfTokenRepository csrf,
            @Value("${auth.registration-enabled:false}") boolean registration) {
        this.jdbc=jdbc; this.passwords=passwords; this.current=current; this.limiter=limiter;
        this.contexts=contexts; this.csrf=csrf; this.registration=registration;
    }
    public record Credentials(String username, String password) {}
    @GetMapping("/csrf") public ApiResponse<?> csrf(CsrfToken token) {
        return ApiResponse.success(Map.of("token", token.getToken(), "headerName", token.getHeaderName(),
            "registrationEnabled", registration));
    }
    @GetMapping("/me") public ApiResponse<?> me() {
        return ApiResponse.success(jdbc.queryForMap("SELECT id, username, role FROM app_user WHERE id = ? AND enabled = TRUE", current.id()));
    }
    @PostMapping("/register") public ApiResponse<?> register(@RequestBody Credentials body, HttpServletRequest req) {
        if (!registration) throw new ApiException(403, "当前未开放注册，请联系管理员");
        limiter.require("rate:register:" + req.getRemoteAddr(), 5, Duration.ofHours(1), "注册请求过于频繁");
        String name=validate(body);
        try {
            long id=Sql.insert(jdbc, "INSERT INTO app_user (username,password_hash,role,enabled,created_at) VALUES (?,?,?,TRUE,NOW(3))",
                name, passwords.encode(body.password()), "USER");
            return ApiResponse.success(Map.of("id",id,"username",name));
        } catch (org.springframework.dao.DuplicateKeyException e) { throw ApiException.conflict("用户名已存在"); }
    }
    @PostMapping("/login") public ApiResponse<?> login(@RequestBody Credentials body, HttpServletRequest req, HttpServletResponse res) {
        limiter.require("rate:login:" + req.getRemoteAddr(), 10, Duration.ofMinutes(10), "登录尝试过于频繁");
        String name=validate(body);
        var rows=jdbc.queryForList("SELECT id,password_hash,role FROM app_user WHERE username = ? AND enabled = TRUE",name);
        // Always run the password KDF, including unknown accounts.
        String hash=rows.isEmpty() ? "$2a$12$0A0z9Mb.YSYKkJQjJSPgkeOjm3c2XBGK8JrTrVaHhZP/qd1N7/2Wy" : (String)rows.get(0).get("password_hash");
        if (!passwords.matches(body.password(),hash) || rows.isEmpty()) throw new ApiException(401,"账号或密码错误");
        var row=rows.get(0);
        var auth=UsernamePasswordAuthenticationToken.authenticated(row.get("id").toString(), null,
            List.of(new SimpleGrantedAuthority("ROLE_"+row.get("role"))));
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(auth,req,res);
        var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth);
        SecurityContextHolder.setContext(context); contexts.saveContext(context,req,res);
        csrf.saveToken(null,req,res);
        return ApiResponse.success(Map.of("id",row.get("id"),"username",name,"role",row.get("role")));
    }
    private String validate(Credentials body) {
        if (body==null || body.username()==null || !body.username().matches("[A-Za-z0-9_@.\\-]{3,64}")
            || body.password()==null || body.password().length()<12 || body.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new ApiException("用户名需为 3–64 位英文或数字，密码需为 12–72 位");
        return body.username().strip().toLowerCase(java.util.Locale.ROOT);
    }
}
