package com.videostudio.repository;

import com.videostudio.common.Json;
import com.videostudio.common.Texts;
import com.videostudio.domain.ContextChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/** context_chunk 读写（embedding 以 JSON 数组存文本列，demo 规模下不引入向量库） */
@Repository
public class ChunkRepository {

    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(long assetId, long jobId, List<ContextChunk> chunks, String embeddingModel) {
        jdbc.update("DELETE FROM context_chunk WHERE job_id = ?", jobId);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>();
        for (ContextChunk c : chunks) {
            args.add(new Object[]{
                    assetId, jobId, c.chunkIndex(), c.startMs(), c.endMs(), c.summary(),
                    Json.write(c.keywords()), Json.write(c.visualKeywords()), c.transcriptText(), c.ocrText(),
                    c.embedding() == null ? null : Json.write(c.embedding()), embeddingModel
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO context_chunk (asset_id, job_id, chunk_index, start_ms, end_ms, summary, keywords,
                                           visual_keywords, transcript_text, ocr_text, embedding, embedding_model)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, args);
    }

    public List<ContextChunk> listByJob(long jobId) {
        return jdbc.query("SELECT * FROM context_chunk WHERE job_id = ? ORDER BY chunk_index ASC", rs -> {
            List<ContextChunk> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new ContextChunk(
                        rs.getInt("chunk_index"),
                        rs.getLong("start_ms"),
                        rs.getLong("end_ms"),
                        rs.getString("summary"),
                        parseList(rs.getString("keywords"), STR_LIST),
                        parseList(rs.getString("visual_keywords"), STR_LIST),
                        rs.getString("transcript_text"),
                        rs.getString("ocr_text"),
                        parseList(rs.getString("embedding"), DOUBLE_LIST),
                        0d));
            }
            return out;
        }, jobId);
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> type) {
        if (Texts.isBlank(json)) {
            return List.of();
        }
        try {
            List<T> list = Json.mapper().readValue(json, type);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    public void deleteByJob(long jobId) {
        jdbc.update("DELETE FROM context_chunk WHERE job_id = ?", jobId);
    }

}
