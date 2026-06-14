package com.example.springbootrag.repository;

import com.example.springbootrag.model.SearchHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PgFtsRepository {

    private final JdbcTemplate jdbc;

    public PgFtsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Postgres native full-text search ranked by ts_rank.
     * Uses websearch_to_tsquery: web-search-style syntax (OR, "phrase", -negation),
     * AND between bare words by default, and never errors on raw user input.
     */
    public List<SearchHit> search(String query, int topK) {
        return jdbc.query(
                "SELECT id, doc_id, chunk_index, content, " +
                        "       ts_rank(tsv, websearch_to_tsquery('english', ?)) AS rank " +
                        "FROM chunks " +
                        "WHERE tsv @@ websearch_to_tsquery('english', ?) " +
                        "ORDER BY rank DESC LIMIT ?",
                (rs, rowNum) -> new SearchHit(
                        rs.getLong("id"),
                        rs.getString("doc_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getDouble("rank")),
                query, query, topK);
    }
}
