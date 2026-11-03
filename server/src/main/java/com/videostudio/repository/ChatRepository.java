package com.videostudio.repository;

import com.videostudio.common.Sql;
import com.videostudio.domain.ChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** chat_message 读写 */
@Repository
public class ChatRepository {

    private static final RowMapper<ChatMessage> ROW = (rs, i) -> new ChatMessage(
            rs.getLong("id"),
            rs.getLong("job_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("citations_json"),
            Sql.instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long save(long jobId, String role, String content, String citationsJson) {
        return Sql.insert(jdbc, """
                INSERT INTO chat_message (job_id, role, content, citations_json, created_at)
                VALUES (?,?,?,?,?)
                """, jobId, role, content, citationsJson, Sql.now());
    }

    public List<ChatMessage> list(long jobId, int limit) {
        List<ChatMessage> rows = jdbc.query(
                "SELECT * FROM chat_message WHERE job_id = ? ORDER BY id DESC LIMIT ?", ROW, jobId, limit);
        return rows.reversed();
    }

    public void clear(long jobId) {
        jdbc.update("DELETE FROM chat_message WHERE job_id = ?", jobId);
    }
}
