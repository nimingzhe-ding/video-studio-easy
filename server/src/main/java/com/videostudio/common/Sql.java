package com.videostudio.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/** JDBC 小工具：插入并返回自增主键、时间戳转换 */
public final class Sql {

    private Sql() {
    }

    public static long insert(JdbcTemplate jdbc, String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("插入未返回主键: " + sql);
        }
        return key.longValue();
    }

    public static Timestamp now() {
        return Timestamp.from(java.time.Instant.now());
    }

    public static Timestamp ts(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static java.time.Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public static <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}
