package com.videostudio.repository;

import com.videostudio.common.Sql;
import com.videostudio.domain.MediaAsset;
import com.videostudio.domain.MediaKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** media_asset 读写 */
@Repository
public class MediaRepository {

    private static final RowMapper<MediaAsset> ROW = (rs, i) -> new MediaAsset(
            rs.getLong("id"),
            rs.getString("name"),
            MediaKind.valueOf(rs.getString("kind")),
            rs.getLong("size_bytes"),
            rs.getLong("duration_ms"),
            (Integer) rs.getObject("width"),
            (Integer) rs.getObject("height"),
            (Double) rs.getObject("fps"),
            rs.getString("video_codec"),
            rs.getString("audio_codec"),
            rs.getString("storage_path"),
            rs.getString("audio_path"),
            rs.getString("frame_dir"),
            rs.getString("meta_json"),
            Sql.instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;
    private final com.videostudio.security.CurrentUser current;

    public MediaRepository(JdbcTemplate jdbc, com.videostudio.security.CurrentUser current) {
        this.current=current;
        this.jdbc = jdbc;
    }

    public long insert(MediaAsset asset) {
        return Sql.insert(jdbc, """
                INSERT INTO media_asset (owner_id, name, kind, size_bytes, duration_ms, width, height, fps,
                                         video_codec, audio_codec, storage_path, audio_path, frame_dir,
                                         meta_json, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                current.id(), asset.name(), asset.kind().name(), asset.sizeBytes(), asset.durationMs(),
                asset.width(), asset.height(), asset.fps(), asset.videoCodec(), asset.audioCodec(),
                asset.storagePath(), asset.audioPath(), asset.frameDir(), asset.metaJson(), Sql.now());
    }

    public Optional<MediaAsset> find(long id) {
        List<MediaAsset> rows = jdbc.query("SELECT * FROM media_asset WHERE id = ?", ROW, id);
        return rows.stream().findFirst();
    }

    public List<MediaAsset> list(int limit) {
        return jdbc.query("SELECT * FROM media_asset WHERE owner_id = ? ORDER BY id DESC LIMIT ?", ROW, current.id(), limit);
    }

    /** 落盘后回写真实路径：上传时先拿 id 再写文件，路径只能在写入之后确定 */
    public void updateStoragePath(long id, String storagePath) {
        jdbc.update("UPDATE media_asset SET storage_path = ? WHERE id = ?", storagePath, id);
    }

    public void updateDerived(long id, long durationMs, Integer width, Integer height, Double fps,
                              String videoCodec, String audioCodec, String audioPath, String frameDir,
                              String metaJson) {
        jdbc.update("""
                UPDATE media_asset SET duration_ms = ?, width = ?, height = ?, fps = ?,
                       video_codec = ?, audio_codec = ?, audio_path = ?, frame_dir = ?, meta_json = ?
                WHERE id = ?
                """, durationMs, width, height, fps, videoCodec, audioCodec, audioPath, frameDir, metaJson, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM media_asset WHERE id = ?", id);
    }
}
