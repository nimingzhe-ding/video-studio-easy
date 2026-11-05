package com.videostudio.repository;

import com.videostudio.common.Sql;
import com.videostudio.domain.JobCheckpoint;
import com.videostudio.domain.JobStage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 任务断点的读写；一个任务最多一行，反复覆盖 */
@Repository
public class CheckpointRepository {

    private static final RowMapper<JobCheckpoint> ROW = (rs, index) -> new JobCheckpoint(
            rs.getLong("job_id"),
            JobStage.valueOf(rs.getString("stage")),
            rs.getString("payload_json"),
            rs.getInt("attempts"),
            Sql.instant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;

    public CheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<JobCheckpoint> find(long jobId) {
        List<JobCheckpoint> rows = jdbc.query(
                "SELECT * FROM job_checkpoint WHERE job_id = ?", ROW, jobId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 记录一次完成。
     * <p>用「先删后插」而不是 MySQL 的 ON DUPLICATE KEY：H2 的 MySQL 兼容模式对后者支持有限，
     * 而单行覆盖的开销完全可以忽略，换来一份 SQL 在两种库上都能跑。
     */
    public void save(long jobId, JobStage stage, String payloadJson, int attempts) {
        jdbc.update("DELETE FROM job_checkpoint WHERE job_id = ?", jobId);
        jdbc.update("INSERT INTO job_checkpoint (job_id, stage, payload_json, attempts) VALUES (?, ?, ?, ?)",
                jobId, stage.name(), payloadJson, attempts);
    }

    public void delete(long jobId) {
        jdbc.update("DELETE FROM job_checkpoint WHERE job_id = ?", jobId);
    }

    /** 计划重启后仍停在中间状态的任务 */
    public List<Long> unfinished(int limit) {
        return jdbc.queryForList(
                "SELECT job_id FROM job_checkpoint WHERE stage <> 'DONE' ORDER BY updated_at ASC LIMIT ?",
                Long.class, Math.max(1, limit));
    }
}
