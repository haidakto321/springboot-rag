package com.example.springbootrag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What the index currently holds for a document, so a re-post can decide between re-embedding,
 * refreshing metadata, and doing nothing at all.
 */
@Repository
public class DocumentRegistry {

    public record Entry(String docId, String docType, String origin, String contentHash,
                        String rawHash, String embedModel, Integer profileVersion,
                        List<String> allowedGroups, int chunkCount) {}

    private final JdbcTemplate jdbc;

    public DocumentRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(long projectId, String docId) {
        return jdbc.query(
                "SELECT doc_id, doc_type, origin, content_hash, raw_hash, embed_model, " +
                        "profile_version, allowed_groups, chunk_count " +
                        "FROM document WHERE project_id = ? AND doc_id = ?",
                (rs, n) -> new Entry(
                        rs.getString("doc_id"), rs.getString("doc_type"), rs.getString("origin"),
                        rs.getString("content_hash"), rs.getString("raw_hash"),
                        rs.getString("embed_model"),
                        (Integer) rs.getObject("profile_version"),
                        toList(rs.getArray("allowed_groups")),
                        rs.getInt("chunk_count")),
                projectId, docId).stream().findFirst();
    }

    public void upsert(long projectId, Entry e) {
        jdbc.update(
                "INSERT INTO document (project_id, doc_id, doc_type, origin, content_hash, raw_hash, " +
                        "embed_model, profile_version, allowed_groups, chunk_count, indexed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, now()) " +
                        "ON CONFLICT (project_id, doc_id) DO UPDATE SET " +
                        "doc_type = EXCLUDED.doc_type, origin = EXCLUDED.origin, " +
                        "content_hash = EXCLUDED.content_hash, raw_hash = EXCLUDED.raw_hash, " +
                        "embed_model = EXCLUDED.embed_model, profile_version = EXCLUDED.profile_version, " +
                        "allowed_groups = EXCLUDED.allowed_groups, chunk_count = EXCLUDED.chunk_count, " +
                        "indexed_at = now()",
                projectId, e.docId(), e.docType(), e.origin(), e.contentHash(), e.rawHash(),
                e.embedModel(), e.profileVersion(),
                PgVectorRepository.toArrayLiteral(e.allowedGroups()), e.chunkCount());
    }

    public void delete(long projectId, String docId) {
        jdbc.update("DELETE FROM document WHERE project_id = ? AND doc_id = ?", projectId, docId);
    }

    private static List<String> toList(java.sql.Array array) {
        try {
            return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not read allowed_groups", e);
        }
    }
}
