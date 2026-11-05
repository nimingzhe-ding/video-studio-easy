package com.videostudio.repository;

import com.videostudio.common.Sql;
import com.videostudio.domain.UploadSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** upload_session / upload_chunk 读写 */
@Repository
public class UploadSessionRepository {

    private static final RowMapper<UploadSession> ROW = (rs, i) -> new UploadSession(
            rs.getString("upload_id"),
            rs.getString("name"),
            rs.getLong("total_bytes"),
            rs.getInt("total_chunks"),
            rs.getInt("chunk_size"),
            rs.getInt("received"),
            rs.getObject("asset_id", Long.class),
            Sql.instant(rs.getTimestamp("created_at")),
            Sql.instant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;

    public UploadSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(UploadSession session) {
        jdbc.update("""
                INSERT INTO upload_session (upload_id, name, total_bytes, total_chunks, chunk_size,
                                            received, asset_id, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                session.uploadId(), session.name(), session.totalBytes(), session.totalChunks(),
                session.chunkSize(), session.received(), session.assetId(),
                Sql.ts(session.createdAt()), Sql.ts(session.updatedAt()));
    }

    public Optional<UploadSession> find(String uploadId) {
        List<UploadSession> rows = jdbc.query("SELECT * FROM upload_session WHERE upload_id = ?", ROW, uploadId);
        return rows.stream().findFirst();
    }

    /**
     * 记录一片已写入。同一片重复上传是幂等的：先删后插，只有真正新增才会让 received 加一。
     *
     * @return true 表示这是一片新内容，false 表示覆盖了已有分片
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean recordChunk(String uploadId, int index, long sizeBytes) {
        boolean existed = hasChunk(uploadId, index);
        jdbc.update("DELETE FROM upload_chunk WHERE upload_id = ? AND chunk_index = ?", uploadId, index);
        jdbc.update("""
                INSERT INTO upload_chunk (upload_id, chunk_index, size_bytes, created_at)
                VALUES (?,?,?,?)
                """, uploadId, index, sizeBytes, Sql.now());
        if (existed) {
            jdbc.update("UPDATE upload_session SET updated_at = ? WHERE upload_id = ?", Sql.now(), uploadId);
        } else {
            jdbc.update("UPDATE upload_session SET received = received + 1, updated_at = ? WHERE upload_id = ?",
                    Sql.now(), uploadId);
        }
        return !existed;
    }

    public boolean hasChunk(String uploadId, int index) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM upload_chunk WHERE upload_id = ? AND chunk_index = ?",
                Integer.class, uploadId, index);
        return count != null && count > 0;
    }

    /** 已收到的分片序号，返回给客户端做断点续传 */
    public List<Integer> receivedIndexes(String uploadId) {
        return jdbc.queryForList(
                "SELECT chunk_index FROM upload_chunk WHERE upload_id = ? ORDER BY chunk_index",
                Integer.class, uploadId);
    }

    /** 已写入分片的字节总和，用于展示真实进度（最后一片通常不足 chunkSize） */
    public long receivedBytes(String uploadId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(size_bytes), 0) FROM upload_chunk WHERE upload_id = ?",
                Long.class, uploadId);
        return sum == null ? 0 : sum;
    }

    public long chunkSize(String uploadId, int index) {
        Long size = jdbc.queryForObject(
                "SELECT size_bytes FROM upload_chunk WHERE upload_id = ? AND chunk_index = ?",
                Long.class, uploadId, index);
        return size == null ? 0 : size;
    }

    public void markFinished(String uploadId, long assetId) {
        jdbc.update("UPDATE upload_session SET asset_id = ?, updated_at = ? WHERE upload_id = ?",
                assetId, Sql.now(), uploadId);
    }

    public void delete(String uploadId) {
        jdbc.update("DELETE FROM upload_chunk WHERE upload_id = ?", uploadId);
        jdbc.update("DELETE FROM upload_session WHERE upload_id = ?", uploadId);
    }

    /** 已经合并完成的会话（其分片目录理论上已删，列表用于兜底回收磁盘） */
    public List<String> finished(java.time.Instant before) {
        return jdbc.queryForList(
                "SELECT upload_id FROM upload_session WHERE asset_id IS NOT NULL AND updated_at < ?",
                String.class, Sql.ts(before));
    }

    /** 清理超过 retention 的未完成会话（进程崩溃后留下的碎片） */
    public List<String> stale(java.time.Instant before) {
        return jdbc.queryForList(
                "SELECT upload_id FROM upload_session WHERE asset_id IS NULL AND updated_at < ?",
                String.class, Sql.ts(before));
    }
}
