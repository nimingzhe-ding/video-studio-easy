package com.videostudio.security;

import com.videostudio.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final JdbcTemplate jdbc; private final PasswordEncoder passwords;
    public AdminUserController(JdbcTemplate jdbc,PasswordEncoder passwords) { this.jdbc=jdbc; this.passwords=passwords; }
    @GetMapping public ApiResponse<?> list(@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(jdbc.queryForList("SELECT id,username,role,enabled,created_at FROM app_user ORDER BY id DESC LIMIT ?",Math.max(1,Math.min(200,limit))));
    }
    @PostMapping public ApiResponse<?> create(@RequestBody AuthController.Credentials body) {
        if(body==null || body.username()==null || !body.username().matches("[A-Za-z0-9_@.\\-]{3,64}")
            || body.password()==null || body.password().length()<12 || body.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new ApiException("用户名需为 3–64 位英文或数字，密码需为至少 12 字符且不超过 72 字节");
        String name=body.username().toLowerCase(java.util.Locale.ROOT);
        try {
            long id=Sql.insert(jdbc,"INSERT INTO app_user(username,password_hash,role,enabled,created_at) VALUES (?,?,'USER',TRUE,NOW(3))",name,passwords.encode(body.password()));
            return ApiResponse.success(Map.of("id",id,"username",name,"role","USER"));
        } catch(org.springframework.dao.DuplicateKeyException ex) { throw ApiException.conflict("用户名已存在"); }
    }
}
