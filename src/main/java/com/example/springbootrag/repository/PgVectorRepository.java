package com.example.springbootrag.repository;

import com.example.springbootrag.model.DocumentSummary;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.web.dto.ChunkView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class PgVectorRepository {

    private final JdbcTemplate jdbc;

    public PgVectorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts one chunk under the given project and returns its generated id. */
    public long insert(long projectId, String docId, int chunkIndex, String content,
                       String sourceFile, String headingPath, float[] embedding, java.time.Instant updatedAt) {
        return jdbc.queryForObject(
                "INSERT INTO chunks (project_id, doc_id, chunk_index, content, source_file, heading_path, embedding, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?) RETURNING id",
                Long.class,
                projectId, docId, chunkIndex, content, sourceFile, headingPath,
                toVectorLiteral(embedding),
                updatedAt == null ? null : java.sql.Timestamp.from(updatedAt));
    }

    /**
     * Vector search with optional project and doc filters.
     * Empty list for either filter means that filter is absent (all projects / all docs).
     */
    public List<SearchHit> search(float[] queryEmbedding, int topK,
                                  List<Long> projectIds, List<String> docIds) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(toVectorLiteral(queryEmbedding));
        if (DocFilter.active(projectIds)) {
            where.append(where.isEmpty() ? " WHERE" : " AND")
                 .append(" project_id IN (").append(DocFilter.placeholders(projectIds.size())).append(")");
            args.addAll(projectIds);
        }
        if (DocFilter.active(docIds)) {
            where.append(where.isEmpty() ? " WHERE" : " AND")
                 .append(" doc_id IN (").append(DocFilter.placeholders(docIds.size())).append(")");
            args.addAll(docIds);
        }
        args.add(topK);
        return jdbc.query(
                "SELECT id, doc_id, chunk_index, content, source_file, heading_path, updated_at, " +
                "       embedding <=> ?::vector AS distance FROM chunks" + where +
                " ORDER BY distance ASC LIMIT ?",
                (rs, n) -> new SearchHit(
                        rs.getLong("id"),
                        rs.getString("doc_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getString("source_file"),
                        rs.getString("heading_path"),
                        1.0 - rs.getDouble("distance"),
                        toInstant(rs.getTimestamp("updated_at"))),
                args.toArray());
    }

    /** All chunks for the given docIds in a project, as SearchHits (score 0; rerank rescoring follows). */
    public List<SearchHit> chunksByDocIds(long projectId, List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(docIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(docIds);
        return jdbc.query(
                "SELECT id, doc_id, chunk_index, content, source_file, heading_path, updated_at " +
                "FROM chunks WHERE project_id = ? AND doc_id IN (" + placeholders + ")",
                (rs, n) -> new SearchHit(
                        rs.getLong("id"), rs.getString("doc_id"), rs.getInt("chunk_index"),
                        rs.getString("content"), rs.getString("source_file"), rs.getString("heading_path"),
                        0.0, toInstant(rs.getTimestamp("updated_at"))),
                args.toArray());
    }

    public void deleteByDocId(long projectId, String docId) {
        jdbc.update("DELETE FROM chunks WHERE project_id = ? AND doc_id = ?", projectId, docId);
    }

    /** One row per ingested document for the given project. */
    public List<DocumentSummary> listDocuments(long projectId) {
        return jdbc.query(
                "SELECT doc_id, MAX(source_file) AS source_file, COUNT(*) AS chunk_count " +
                        "FROM chunks WHERE project_id = ? GROUP BY doc_id ORDER BY doc_id",
                (rs, rowNum) -> new DocumentSummary(
                        rs.getString("doc_id"),
                        rs.getString("source_file"),
                        rs.getInt("chunk_count")),
                projectId);
    }

    /** All chunks of one document ordered by chunk index, scoped to the given project. */
    public List<ChunkView> listChunks(long projectId, String docId) {
        return jdbc.query(
                "SELECT chunk_index, heading_path, content " +
                        "FROM chunks WHERE project_id = ? AND doc_id = ? ORDER BY chunk_index",
                (rs, rowNum) -> new ChunkView(
                        rs.getInt("chunk_index"),
                        rs.getString("heading_path"),
                        rs.getString("content")),
                projectId, docId);
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

    static java.time.Instant toInstant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
