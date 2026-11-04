package com.videostudio.repository;

import com.videostudio.common.Sql;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.domain.AnalysisReport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** analysis_report 读写 */
@Repository
public class ReportRepository {

    private static final RowMapper<AnalysisReport> ROW = (rs, i) -> new AnalysisReport(
            rs.getLong("id"),
            rs.getLong("job_id"),
            rs.getLong("asset_id"),
            rs.getString("title"),
            AnalysisMode.of(rs.getString("mode")),
            rs.getString("markdown"),
            rs.getString("outline_json"),
            rs.getString("trace_json"),
            rs.getString("docx_path"),
            Sql.instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(AnalysisReport report) {
        jdbc.update("DELETE FROM analysis_report WHERE job_id = ?", report.jobId());
        return Sql.insert(jdbc, """
                INSERT INTO analysis_report (job_id, asset_id, title, mode, markdown, outline_json, trace_json,
                                             docx_path, created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                report.jobId(), report.assetId(), report.title(), report.mode().name(), report.markdown(),
                report.outlineJson(), report.traceJson(), report.docxPath(), Sql.now());
    }

    public Optional<AnalysisReport> findByJob(long jobId) {
        List<AnalysisReport> rows = jdbc.query("SELECT * FROM analysis_report WHERE job_id = ? ORDER BY id DESC",
                ROW, jobId);
        return rows.stream().findFirst();
    }

    public void updateDocxPath(long jobId, String path) {
        jdbc.update("UPDATE analysis_report SET docx_path = ? WHERE job_id = ?", path, jobId);
    }

    public void deleteByJob(long jobId) {
        jdbc.update("DELETE FROM analysis_report WHERE job_id = ?", jobId);
    }

}
