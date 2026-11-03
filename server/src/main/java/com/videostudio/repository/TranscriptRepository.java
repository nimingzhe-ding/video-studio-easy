package com.videostudio.repository;

import com.videostudio.domain.TranscriptSegment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/** transcript_segment 读写 */
@Repository
public class TranscriptRepository {

    private static final RowMapper<TranscriptSegment> ROW = (rs, i) -> new TranscriptSegment(
            rs.getLong("start_ms"),
            rs.getLong("end_ms"),
            rs.getString("text"),
            rs.getString("speaker"),
            (Double) rs.getObject("confidence"),
            rs.getString("source"));

    private final JdbcTemplate jdbc;

    public TranscriptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(long assetId, long jobId, List<TranscriptSegment> segments) {
        jdbc.update("DELETE FROM transcript_segment WHERE job_id = ?", jobId);
        if (segments == null || segments.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegment s = segments.get(i);
            args.add(new Object[]{assetId, jobId, i, s.startMs(), s.endMs(), s.text(), s.speaker(), s.confidence(), s.source()});
        }
        jdbc.batchUpdate("""
                INSERT INTO transcript_segment (asset_id, job_id, seq, start_ms, end_ms, text, speaker, confidence, source)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, args);
    }

    public List<TranscriptSegment> listByJob(long jobId) {
        return jdbc.query("SELECT * FROM transcript_segment WHERE job_id = ? ORDER BY start_ms ASC", ROW, jobId);
    }
}
