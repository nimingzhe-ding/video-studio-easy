package com.videostudio.repository;

import com.videostudio.common.Sql;
import com.videostudio.domain.JobEvent;
import com.videostudio.domain.JobStage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** job_event 读写：事件序列是 SSE 的唯一真源，刷新页面后可回放 */
@Repository
public class JobEventRepository {

    private static final RowMapper<JobEvent> ROW = (rs, i) -> new JobEvent(
            rs.getLong("seq"),
            JobStage.valueOf(rs.getString("stage")),
            rs.getString("type"),
            rs.getString("level"),
            rs.getInt("progress"),
            com.videostudio.common.SafeErrors.redact(rs.getString("message")),
            rs.getString("payload_json"),
            Sql.instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public JobEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long append(long jobId, long seq, JobEvent event) {
        return Sql.insert(jdbc, """
                INSERT INTO job_event (job_id, seq, stage, type, level, progress, message, payload_json, created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                jobId, seq, event.stage().name(), event.type(), event.level(), event.progress(),
                com.videostudio.common.SafeErrors.redact(event.message()), event.payloadJson(), Sql.now());
    }

    public List<JobEvent> list(long jobId, long afterSeq, int limit) {
        return jdbc.query("""
                SELECT * FROM job_event WHERE job_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?
                """, ROW, jobId, afterSeq, limit);
    }

    public long lastSeq(long jobId) {
        Long seq = jdbc.query("SELECT COALESCE(MAX(seq), 0) FROM job_event WHERE job_id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, jobId);
        return seq == null ? 0 : seq;
    }

    public void deleteByJob(long jobId) {
        jdbc.update("DELETE FROM job_event WHERE job_id = ?", jobId);
    }

    /** 事件序号生成器（进程内自增，配合DB持久化保证单调） */
    public AtomicLong sequencer(long jobId) {
        return new AtomicLong(lastSeq(jobId));
    }
}
