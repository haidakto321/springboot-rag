package com.example.springbootrag.repository;

import com.example.springbootrag.model.SearchHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PgVectorRepository {

    private final JdbcTemplate jdbc;

    public PgVectorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts one chunk and returns its generated id. */
    public long insert(String docId, int chunkIndex, String content, float[] embedding) {
        return jdbc.queryForObject(
                "INSERT INTO chunks (doc_id, chunk_index, content, embedding) " +
                        "VALUES (?, ?, ?, ?::vector) RETURNING id",
                Long.class,
                docId, chunkIndex, content, toVectorLiteral(embedding));
    }

    /** Vector search: lower cosine distance = more similar, so we sort ascending and invert to a score. */
    public List<SearchHit> search(float[] queryEmbedding, int topK) {
        return jdbc.query(
                "SELECT id, doc_id, chunk_index, content, " +
                        "       embedding <=> ?::vector AS distance " +
                        "FROM chunks ORDER BY distance ASC LIMIT ?",
                (rs, rowNum) -> new SearchHit(
                        rs.getLong("id"),
                        rs.getString("doc_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        1.0 - rs.getDouble("distance")),
                toVectorLiteral(queryEmbedding), topK);
    }

    public void deleteByDocId(String docId) {
        jdbc.update("DELETE FROM chunks WHERE doc_id = ?", docId);
    }

    /** pgvector text format: "[0.1,0.2,0.3]". */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
