package com.videostudio.repository;

import com.videostudio.common.Sql;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.JobStage;
import com.videostudio.domain.JobStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** analysis_job 读写 */
@Repository
public class JobRepository {

    private static final RowMapper<AnalysisJob> ROW = (rs, i) -> new AnalysisJob(
            rs.getLong("id"),
            rs.getLong("asset_id"),
            AnalysisMode.of(rs.getString("mode")),
            rs.getString("mode_source"),
            rs.getString("mode_reason"),
            rs.getString("title"),
            rs.getString("focus"),
            JobStatus.valueOf(rs.getString("status")),
            JobStage.valueOf(rs.getString("stage")),
            rs.getInt("progress"),
            rs.getString("error"),
            rs.getBoolean("degraded"),
            rs.getString("notices"),
            Sql.instant(rs.getTimestamp("created_at")),
            Sql.instant(rs.getTimestamp("updated_at")),
            Sql.instant(rs.getTimestamp("finished_at")));

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long assetId, AnalysisMode mode, String modeSource, String modeReason,
                       String title, String focus) {
        return Sql.insert(jdbc, """
                INSERT INTO analysis_job (asset_id, mode, mode_source, mode_reason, title, focus,
                                          status, stage, progress, degraded, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                assetId, mode.name(), modeSource, modeReason, title, focus,
                JobStatus.PENDING.name(), JobStage.QUEUED.name(), 0, false, Sql.now(), Sql.now());
    }

    public Optional<AnalysisJob> find(long id) {
        List<AnalysisJob> rows = jdbc.query("SELECT * FROM analysis_job WHERE id = ?", ROW, id);
        return rows.stream().findFirst();
    }

    public List<AnalysisJob> list(int limit) {
        return jdbc.query("SELECT * FROM analysis_job ORDER BY id DESC LIMIT ?", ROW, limit);
    }

    public List<AnalysisJob> listByAsset(long assetId) {
        return jdbc.query("SELECT * FROM analysis_job WHERE asset_id = ? ORDER BY id DESC", ROW, assetId);
    }

    public void updateProgress(long id, JobStage stage, int progress) {
        jdbc.update("""
                UPDATE analysis_job SET stage = ?, progress = ?, updated_at = ?, status = ?
                WHERE id = ? AND status IN ('PENDING','RUNNING')
                """, stage.name(), progress, Sql.now(), JobStatus.RUNNING.name(), id);
    }

    public void markRunning(long id) {
        jdbc.update("UPDATE analysis_job SET status = ?, updated_at = ? WHERE id = ?",
                JobStatus.RUNNING.name(), Sql.now(), id);
    }

    public void markSucceeded(long id) {
        jdbc.update("""
                UPDATE analysis_job SET status = ?, stage = ?, progress = 100, updated_at = ?, finished_at = ?
                WHERE id = ?
                """, JobStatus.SUCCEEDED.name(), JobStage.DONE.name(), Sql.now(), Sql.now(), id);
    }

    public void markFailed(long id, String error) {
        jdbc.update("""
                UPDATE analysis_job SET status = ?, stage = ?, error = ?, updated_at = ?, finished_at = ?
                WHERE id = ?
                """, JobStatus.FAILED.name(), JobStage.FAILED.name(), error, Sql.now(), Sql.now(), id);
    }

    public void markCanceled(long id) {
        jdbc.update("""
                UPDATE analysis_job SET status = ?, updated_at = ?, finished_at = ? WHERE id = ?
                """, JobStatus.CANCELED.name(), Sql.now(), Sql.now(), id);
    }

    public void addNotice(long id, String notice) {
        if (notice == null || notice.isBlank()) {
            return;
        }
        List<String> rows = jdbc.queryForList("SELECT notices FROM analysis_job WHERE id = ?", String.class, id);
        String existing = rows.isEmpty() ? null : rows.get(0);
        String merged = existing == null || existing.isBlank() ? notice : existing + "\n" + notice;
        jdbc.update("UPDATE analysis_job SET degraded = TRUE, notices = ?, updated_at = ? WHERE id = ?",
                merged, Sql.now(), id);
    }

    /** 启动时把上次进程遗留的 RUNNING 任务标记为失败，避免前端永远等待 */
    public int failZombies(String reason) {
        return jdbc.update("""
                UPDATE analysis_job SET status = ?, stage = ?, error = ?, updated_at = ?, finished_at = ?
                WHERE status IN ('PENDING','RUNNING')
                """, JobStatus.FAILED.name(), JobStage.FAILED.name(), reason, Sql.now(), Sql.now());
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM analysis_job WHERE id = ?", id);
    }

    public List<Long> idsByAsset(long assetId) {
        return jdbc.queryForList("SELECT id FROM analysis_job WHERE asset_id = ?", Long.class, assetId);
    }

}
