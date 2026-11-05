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
     * 使用 MySQL 原子 upsert 保存阶段，避免删除与插入之间的断点丢失。
     */
    public void save(long jobId, JobStage stage, String payloadJson, int attempts) {
        jdbc.update("INSERT INTO job_checkpoint (job_id, stage, payload_json, attempts) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE stage=VALUES(stage),payload_json=VALUES(payload_json),attempts=VALUES(attempts),updated_at=NOW(3)",
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
