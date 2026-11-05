package com.videostudio.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdmin implements ApplicationRunner {
    private final JdbcTemplate jdbc; private final PasswordEncoder passwords;
    private final String username; private final String password;
    public BootstrapAdmin(JdbcTemplate jdbc, PasswordEncoder passwords,
        @Value("${auth.admin-username:}") String username, @Value("${auth.admin-password:}") String password) {
        this.jdbc=jdbc; this.passwords=passwords; this.username=username; this.password=password;
    }
    @Override public void run(ApplicationArguments args) {
        if (username.isBlank()) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Long.class)==0)
                throw new IllegalStateException("首次部署必须设置 AUTH_ADMIN_USERNAME 与 AUTH_ADMIN_PASSWORD");
            return;
        }
        String name=username.toLowerCase(java.util.Locale.ROOT);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?",Long.class,name)>0) return;
        if (!name.matches("[a-z0-9_@.\\-]{3,64}") || password.length()<12 || password.length()>72)
            throw new IllegalStateException("管理员用户名或密码不符合要求，密码至少 12 位");
        jdbc.update("INSERT INTO app_user (username,password_hash,role,enabled,created_at) VALUES (?,?,'ADMIN',TRUE,NOW(3))",
            name,passwords.encode(password));
    }
}
