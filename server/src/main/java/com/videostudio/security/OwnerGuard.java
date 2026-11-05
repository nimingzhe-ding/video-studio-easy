package com.videostudio.security;

import com.videostudio.common.ApiException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.regex.Pattern;

@Component
public class OwnerGuard implements HandlerInterceptor {
    private static final Pattern RESOURCE = Pattern.compile("^/api/(media|jobs)/(\\d+)(?:/.*)?$");
    private final JdbcTemplate jdbc;
    private final CurrentUser user;
    public OwnerGuard(JdbcTemplate jdbc, CurrentUser user) { this.jdbc = jdbc; this.user = user; }
    public void media(long id) {
        if (!Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE id = ? AND owner_id = ?",
                Long.class, id, user.id()))) throw ApiException.notFound("媒体不存在");
    }
    public void job(long id) {
        if (!Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_job j JOIN media_asset m ON m.id = j.asset_id WHERE j.id = ? AND m.owner_id = ?",
                Long.class, id, user.id()))) throw ApiException.notFound("任务不存在");
    }
    @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (req.getDispatcherType() == DispatcherType.ASYNC) return true;
        var match = RESOURCE.matcher(req.getRequestURI());
        if (match.matches()) {
            long id = Long.parseLong(match.group(2));
            if ("media".equals(match.group(1))) media(id); else job(id);
        }
        return true;
    }
}
