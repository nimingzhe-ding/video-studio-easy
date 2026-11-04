package com.videostudio.repository;

import com.videostudio.domain.VisualEvidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** visual_evidence 读写 */
@Repository
public class VisualRepository {

    private static final RowMapper<VisualEvidence> ROW = (rs, i) -> new VisualEvidence(
            rs.getLong("time_ms"),
            rs.getString("frame_path"),
            rs.getString("ocr_text"),
            rs.getString("caption"),
            rs.getString("hash"),
            rs.getString("source"));

    private final JdbcTemplate jdbc;

    public VisualRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(long assetId, long jobId, List<VisualEvidence> items) {
        jdbc.update("DELETE FROM visual_evidence WHERE job_id = ?", jobId);
        if (items == null || items.isEmpty()) {
            return;
        }
        java.util.List<Object[]> args = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            VisualEvidence v = items.get(i);
            args.add(new Object[]{assetId, jobId, i, v.timeMs(), v.framePath(), v.ocrText(), v.caption(), v.hash(), v.source()});
        }
        jdbc.batchUpdate("""
                INSERT INTO visual_evidence (asset_id, job_id, seq, time_ms, frame_path, ocr_text, caption, hash, source)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, args);
    }

    public List<VisualEvidence> listByJob(long jobId) {
        return jdbc.query("SELECT * FROM visual_evidence WHERE job_id = ? ORDER BY time_ms ASC", ROW, jobId);
    }

    public void deleteByJob(long jobId) {
        jdbc.update("DELETE FROM visual_evidence WHERE job_id = ?", jobId);
    }

}
