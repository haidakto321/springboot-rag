package com.example.springbootrag.repository;

import com.example.springbootrag.model.SearchHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class PgFtsRepository {

    private final JdbcTemplate jdbc;

    public PgFtsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Postgres native full-text search ranked by ts_rank.
     * Uses websearch_to_tsquery: web-search-style syntax (OR, "phrase", -negation).
     * Empty projectIds / docIds list means that filter is absent.
     */
    public List<SearchHit> search(String query, int topK,
                                  List<Long> projectIds, List<String> docIds) {
        String projectClause = DocFilter.active(projectIds)
                ? " AND project_id IN (" + DocFilter.placeholders(projectIds.size()) + ")"
                : "";
        String docClause = DocFilter.active(docIds)
                ? " AND doc_id IN (" + DocFilter.placeholders(docIds.size()) + ")"
                : "";
        List<Object> args = new ArrayList<>();
        args.add(query);
        args.add(query);
        if (DocFilter.active(projectIds)) args.addAll(projectIds);
        if (DocFilter.active(docIds)) args.addAll(docIds);
        args.add(topK);
        return jdbc.query(
                "SELECT id, doc_id, chunk_index, content, source_file, heading_path, updated_at, " +
                        "       ts_rank(tsv, websearch_to_tsquery('english', ?)) AS rank " +
                        "FROM chunks " +
                        "WHERE tsv @@ websearch_to_tsquery('english', ?)" + projectClause + docClause + " " +
                        "ORDER BY rank DESC LIMIT ?",
                (rs, rowNum) -> new SearchHit(
                        rs.getLong("id"),
                        rs.getString("doc_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getString("source_file"),
                        rs.getString("heading_path"),
                        rs.getDouble("rank"),
                        PgVectorRepository.toInstant(rs.getTimestamp("updated_at"))),
                args.toArray());
    }
}
