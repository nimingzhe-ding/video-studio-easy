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
    private final com.videostudio.security.CurrentUser current;

    public JobRepository(JdbcTemplate jdbc, com.videostudio.security.CurrentUser current) {
        this.current=current;
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
        return jdbc.query("SELECT j.* FROM analysis_job j JOIN media_asset m ON m.id=j.asset_id WHERE m.owner_id=? ORDER BY j.id DESC LIMIT ?", ROW, current.id(), limit);
    }

    /**
     * 某个素材下的全部任务，不做归属过滤。
     * <p>只给流水线内部用（证据复用等后台线程场景）：那里没有 SecurityContext，
     * 也不能因为「谁在跑」而改变取值。对外接口请用 {@link #listByAssetForOwner(long)}。
     */
    public List<AnalysisJob> listByAsset(long assetId) {
        return jdbc.query("SELECT * FROM analysis_job WHERE asset_id = ? ORDER BY id DESC", ROW, assetId);
    }

    /**
     * 给接口层用的版本：带上 owner 过滤。
     * <p>入口的 {@link com.videostudio.security.OwnerGuard} 已经校验过素材归属，
     * 这里再过滤一次是纵深防御——仓储层不应该依赖调用方一定先做过校验。
     */
    public List<AnalysisJob> listByAssetForOwner(long assetId) {
        return jdbc.query("""
                SELECT j.* FROM analysis_job j JOIN media_asset m ON m.id = j.asset_id
                WHERE j.asset_id = ? AND m.owner_id = ? ORDER BY j.id DESC
                """, ROW, assetId, current.id());
    }

    public void updateProgress(long id, JobStage stage, int progress) {
        jdbc.update("""
                UPDATE analysis_job SET stage = ?, progress = ?, updated_at = ?, status = ?
                WHERE id = ? AND status IN ('PENDING','RUNNING')
                """, stage.name(), progress, Sql.now(), JobStatus.RUNNING.name(), id);
    }

    public void markRunning(long id) {
        jdbc.update("UPDATE analysis_job SET status = ?, updated_at = ? WHERE id = ? AND status IN ('PENDING','RUNNING','FAILED')",
                JobStatus.RUNNING.name(), Sql.now(), id);
    }

    public boolean markSucceeded(long id) {
        return markSucceeded(id, false);
    }

    /**
     * @param degraded 本次报告是否在证据不完整的情况下产出（目前只有向量索引用得上：
     *                 索引失败不会终止任务，但语义召回这一路确实缺失，前端应当能看见）
     */
    public boolean markSucceeded(long id, boolean degraded) {
        return jdbc.update("""
                UPDATE analysis_job SET status = ?, stage = ?, progress = 100, updated_at = ?, finished_at = ?,
                                       degraded = ?
                WHERE id = ? AND status = 'RUNNING'
                """, JobStatus.SUCCEEDED.name(), JobStage.DONE.name(), Sql.now(), Sql.now(),
                degraded, id) == 1;
    }

    public void markFailed(long id, String error) {
        jdbc.update("""
                UPDATE analysis_job SET status = ?, stage = ?, error = ?, updated_at = ?, finished_at = ?
                WHERE id = ? AND status NOT IN ('CANCELED','SUCCEEDED')
                """, JobStatus.FAILED.name(), JobStage.FAILED.name(), error, Sql.now(), Sql.now(), id);
    }

    public void addNotice(long id, String notice) {
        if (notice == null || notice.isBlank()) {
            return;
        }
        List<String> rows = jdbc.queryForList("SELECT notices FROM analysis_job WHERE id = ?", String.class, id);
        String existing = rows.isEmpty() ? null : rows.get(0);
        String merged = existing == null || existing.isBlank() ? notice : existing + "\n" + notice;
        jdbc.update("UPDATE analysis_job SET notices = ?, updated_at = ? WHERE id = ?",
                merged, Sql.now(), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM analysis_job WHERE id = ?", id);
    }

    public List<Long> idsByAsset(long assetId) {
        return jdbc.queryForList("SELECT id FROM analysis_job WHERE asset_id = ?", Long.class, assetId);
    }

}
