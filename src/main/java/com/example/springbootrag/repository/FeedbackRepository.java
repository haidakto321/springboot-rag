package com.example.springbootrag.repository;

import com.example.springbootrag.model.FeedbackLabel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Store of human relevance labels on individual chunks (ROADMAP "Option A").
 *
 * <p>One label per (project, doc, chunk, query): a repeat vote overwrites the previous one, so
 * consumers read clean (query, chunk, relevant) triples without dedupe logic. Un-voting deletes
 * the row.
 */
@Repository
public class FeedbackRepository {

    private static final RowMapper<FeedbackLabel> MAPPER = (rs, n) -> new FeedbackLabel(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getString("query_text"),
            rs.getString("doc_id"),
            rs.getInt("chunk_index"),
            rs.getString("rating"),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the label, or replaces the rating already stored for the same key. */
    public void upsert(long projectId, String query, String docId, int chunkIndex, String rating) {
        jdbc.update("""
            INSERT INTO chunk_feedback (project_id, query_text, doc_id, chunk_index, rating)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (project_id, doc_id, chunk_index, query_text)
            DO UPDATE SET rating = EXCLUDED.rating, updated_at = now()
            """, projectId, query, docId, chunkIndex, rating);
    }

    /** Removes a label (the user un-toggled the thumb). Returns rows deleted: 0 when none existed. */
    public int clear(long projectId, String query, String docId, int chunkIndex) {
        return jdbc.update("""
            DELETE FROM chunk_feedback
            WHERE project_id = ? AND doc_id = ? AND chunk_index = ? AND query_text = ?
            """, projectId, docId, chunkIndex, query);
    }

    /** Newest first. Both filters are optional; a null value drops that condition. */
    public List<FeedbackLabel> list(Long projectId, String query, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, project_id, query_text, doc_id, chunk_index, rating, updated_at
            FROM chunk_feedback WHERE 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (projectId != null) {
            sql.append(" AND project_id = ?");
            args.add(projectId);
        }
        if (query != null) {
            sql.append(" AND query_text = ?");
            args.add(query);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public int count(Long projectId) {
        return projectId == null
                ? jdbc.queryForObject("SELECT count(*) FROM chunk_feedback", Integer.class)
                : jdbc.queryForObject(
                        "SELECT count(*) FROM chunk_feedback WHERE project_id = ?", Integer.class, projectId);
    }
}
