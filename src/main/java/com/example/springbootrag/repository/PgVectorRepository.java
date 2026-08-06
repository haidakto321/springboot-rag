package com.example.springbootrag.repository;

import com.example.springbootrag.model.DocumentSummary;
import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.security.SearchContext;
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

    /**
     * Inserts one chunk under the given project and returns its generated id.
     * {@code allowedGroups} is the access label; an empty label makes the chunk unreadable, which
     * is why {@code IngestService} always resolves a non-empty default.
     */
    public long insert(long projectId, String docId, int chunkIndex, String content,
                       String sourceFile, String headingPath, float[] embedding,
                       java.time.Instant updatedAt, List<String> allowedGroups) {
        return insert(projectId, docId, chunkIndex, content, sourceFile, headingPath, embedding,
                updatedAt, allowedGroups, null, null);
    }

    /**
     * Record-aware insert. {@code metadataJson} is the values/prov/conf object for this chunk;
     * null or blank stores an empty object, which is what the markdown path does.
     */
    public long insert(long projectId, String docId, int chunkIndex, String content,
                       String sourceFile, String headingPath, float[] embedding,
                       java.time.Instant updatedAt, List<String> allowedGroups,
                       String docType, String metadataJson) {
        String groupsLiteral = toArrayLiteral(allowedGroups);
        return jdbc.queryForObject(
                "INSERT INTO chunks (project_id, doc_id, chunk_index, content, source_file, heading_path, " +
                        "embedding, updated_at, allowed_groups, doc_type, metadata) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?, ?::text[], ?, ?::jsonb) RETURNING id",
                Long.class,
                projectId, docId, chunkIndex, content, sourceFile, headingPath,
                toVectorLiteral(embedding),
                updatedAt == null ? null : java.sql.Timestamp.from(updatedAt),
                groupsLiteral, docType,
                metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson);
    }

    /** Rewrites one chunk's metadata without touching its vector. */
    public void updateMetadata(long projectId, String docId, int chunkIndex, String metadataJson) {
        jdbc.update("UPDATE chunks SET metadata = ?::jsonb " +
                        "WHERE project_id = ? AND doc_id = ? AND chunk_index = ?",
                metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson,
                projectId, docId, chunkIndex);
    }

    /**
     * Vector search with optional project and doc filters, always filtered by access labels.
     * Empty list for either optional filter means that filter is absent (all projects / all docs).
     */
    public List<SearchHit> search(SearchContext ctx, float[] queryEmbedding, int topK,
                                  List<Long> projectIds, List<String> docIds) {
        return search(ctx, queryEmbedding, topK, projectIds, docIds, MetadataFilter.none());
    }

    /** Same query, additionally narrowed by structured record metadata. */
    public List<SearchHit> search(SearchContext ctx, float[] queryEmbedding, int topK,
                                  List<Long> projectIds, List<String> docIds,
                                  MetadataFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE" + DocFilter.groupClause(ctx.groups()));
        List<Object> args = new ArrayList<>();
        args.add(toVectorLiteral(queryEmbedding));
        args.addAll(ctx.groups());
        if (DocFilter.active(projectIds)) {
            where.append(" AND project_id IN (").append(DocFilter.placeholders(projectIds.size())).append(")");
            args.addAll(projectIds);
        }
        if (DocFilter.active(docIds)) {
            where.append(" AND doc_id IN (").append(DocFilter.placeholders(docIds.size())).append(")");
            args.addAll(docIds);
        }
        FilterSql.Fragment meta = FilterSql.render(filter);
        where.append(meta.sql());
        args.addAll(meta.args());
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

    /**
     * All chunks for the given docIds in a project, as SearchHits (score 0; rerank rescoring follows).
     * Used by graph expansion, which must not become a way around the access filter.
     */
    public List<SearchHit> chunksByDocIds(SearchContext ctx, long projectId, List<String> docIds) {
        return chunksByDocIds(ctx, projectId, docIds, MetadataFilter.none());
    }

    /**
     * Same, narrowed by record metadata. Graph expansion uses this, so a neighbour that fails the
     * caller's filter is dropped here - expansion must not become a way around a filter, exactly
     * as it must not become a way around an access label.
     */
    public List<SearchHit> chunksByDocIds(SearchContext ctx, long projectId, List<String> docIds,
                                          MetadataFilter filter) {
        if (docIds == null || docIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(docIds.size(), "?"));
        FilterSql.Fragment meta = FilterSql.render(filter);
        List<Object> args = new ArrayList<>();
        args.addAll(ctx.groups());
        args.add(projectId);
        args.addAll(docIds);
        args.addAll(meta.args());
        return jdbc.query(
                "SELECT id, doc_id, chunk_index, content, source_file, heading_path, updated_at " +
                "FROM chunks WHERE" + DocFilter.groupClause(ctx.groups()) +
                " AND project_id = ? AND doc_id IN (" + placeholders + ")" + meta.sql(),
                (rs, n) -> new SearchHit(
                        rs.getLong("id"), rs.getString("doc_id"), rs.getInt("chunk_index"),
                        rs.getString("content"), rs.getString("source_file"), rs.getString("heading_path"),
                        0.0, toInstant(rs.getTimestamp("updated_at"))),
                args.toArray());
    }

    /** Chunks for the given ids, as SearchHits (score 0; rerank rescoring follows). */
    public List<SearchHit> chunksByIds(SearchContext ctx, List<Long> ids) {
        return chunksByIds(ctx, ids, MetadataFilter.none());
    }

    /** Same, narrowed by record metadata - the semantic half of graph expansion. */
    public List<SearchHit> chunksByIds(SearchContext ctx, List<Long> ids, MetadataFilter filter) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        FilterSql.Fragment meta = FilterSql.render(filter);
        List<Object> args = new ArrayList<>(ctx.groups());
        args.addAll(ids);
        args.addAll(meta.args());
        return jdbc.query(
                "SELECT id, doc_id, chunk_index, content, source_file, heading_path, updated_at " +
                "FROM chunks WHERE" + DocFilter.groupClause(ctx.groups()) +
                " AND id IN (" + placeholders + ")" + meta.sql(),
                (rs, n) -> new SearchHit(
                        rs.getLong("id"), rs.getString("doc_id"), rs.getInt("chunk_index"),
                        rs.getString("content"), rs.getString("source_file"), rs.getString("heading_path"),
                        0.0, toInstant(rs.getTimestamp("updated_at"))),
                args.toArray());
    }

    public void deleteByDocId(long projectId, String docId) {
        jdbc.update("DELETE FROM chunks WHERE project_id = ? AND doc_id = ?", projectId, docId);
    }

    /**
     * One row per ingested document for the given project, restricted to documents the caller can
     * read. A document title is data too: listing one the caller cannot open is still a leak.
     */
    public List<DocumentSummary> listDocuments(SearchContext ctx, long projectId) {
        List<Object> args = new ArrayList<>(ctx.groups());
        args.add(projectId);
        return jdbc.query(
                "SELECT doc_id, MAX(source_file) AS source_file, COUNT(*) AS chunk_count " +
                        "FROM chunks WHERE" + DocFilter.groupClause(ctx.groups()) +
                        " AND project_id = ? GROUP BY doc_id ORDER BY doc_id",
                (rs, rowNum) -> new DocumentSummary(
                        rs.getString("doc_id"),
                        rs.getString("source_file"),
                        rs.getInt("chunk_count")),
                args.toArray());
    }

    /** All readable chunks of one document ordered by chunk index, scoped to the given project. */
    public List<ChunkView> listChunks(SearchContext ctx, long projectId, String docId) {
        List<Object> args = new ArrayList<>(ctx.groups());
        args.add(projectId);
        args.add(docId);
        return jdbc.query(
                "SELECT chunk_index, heading_path, content " +
                        "FROM chunks WHERE" + DocFilter.groupClause(ctx.groups()) +
                        " AND project_id = ? AND doc_id = ? ORDER BY chunk_index",
                (rs, rowNum) -> new ChunkView(
                        rs.getInt("chunk_index"),
                        rs.getString("heading_path"),
                        rs.getString("content")),
                args.toArray());
    }

    /** True when the caller may read this exact chunk. Used before accepting a feedback label. */
    public boolean isVisible(SearchContext ctx, long projectId, String docId, int chunkIndex) {
        List<Object> args = new ArrayList<>(ctx.groups());
        args.add(projectId);
        args.add(docId);
        args.add(chunkIndex);
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE" + DocFilter.groupClause(ctx.groups()) +
                        " AND project_id = ? AND doc_id = ? AND chunk_index = ?",
                Integer.class, args.toArray());
        return found != null && found > 0;
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

    /** Postgres array literal: {"public","hr"}. Quotes and backslashes inside a name are escaped. */
    static String toArrayLiteral(List<String> values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"')
              .append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
              .append('"');
        }
        return sb.append('}').toString();
    }

    static java.time.Instant toInstant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
