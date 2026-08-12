package com.example.springbootrag.repository;

import com.example.springbootrag.security.SearchContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The holding pen: documents that tripped {@link com.example.springbootrag.guard.SecretScanner}
 * and were therefore never indexed.
 *
 * <p>Reads are group-scoped like every other read in this system. The pen stores the RAW text of a
 * held document, so an unscoped listing would hand out exactly the content quarantine exists to
 * keep out of reach.
 */
@Repository
public class QuarantineRepository {

    /** One held document. {@code createdAt} is ignored on write and filled by the database. */
    public record Held(String docId, String origin, String sourceFile, String docType,
                       String rawText, String findingsJson, List<String> allowedGroups,
                       Instant createdAt) {}

    private static final RowMapper<Held> MAPPER = (rs, n) -> new Held(
            rs.getString("doc_id"),
            rs.getString("origin"),
            rs.getString("source_file"),
            rs.getString("doc_type"),
            rs.getString("raw_text"),
            rs.getString("findings"),
            toList(rs.getArray("allowed_groups")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public QuarantineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores, or replaces what is already held under this doc id (the pipeline retries). */
    public void hold(long projectId, Held h) {
        jdbc.update("""
            INSERT INTO quarantine (project_id, doc_id, origin, source_file, doc_type, raw_text,
                                    findings, allowed_groups)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::text[])
            ON CONFLICT (project_id, doc_id) DO UPDATE SET
                origin = EXCLUDED.origin, source_file = EXCLUDED.source_file,
                doc_type = EXCLUDED.doc_type, raw_text = EXCLUDED.raw_text,
                findings = EXCLUDED.findings, allowed_groups = EXCLUDED.allowed_groups,
                created_at = now()
            """, projectId, h.docId(), h.origin(), h.sourceFile(), h.docType(), h.rawText(),
                h.findingsJson(), PgVectorRepository.toArrayLiteral(h.allowedGroups()));
    }

    /** Newest first, restricted to what the caller's groups overlap. */
    public List<Held> list(SearchContext ctx, long projectId) {
        if (ctx.readsNothing()) return List.of();
        List<Object> args = new ArrayList<>(ctx.groups());
        args.add(projectId);
        return jdbc.query("""
            SELECT doc_id, origin, source_file, doc_type, raw_text, findings::text AS findings,
                   allowed_groups, created_at
            FROM quarantine
            WHERE""" + DocFilter.groupClause(ctx.groups()) + """
             AND project_id = ?
            ORDER BY created_at DESC
            """, MAPPER, args.toArray());
    }

    public Optional<Held> find(SearchContext ctx, long projectId, String docId) {
        if (ctx.readsNothing()) return Optional.empty();
        List<Object> args = new ArrayList<>(ctx.groups());
        args.add(projectId);
        args.add(docId);
        return jdbc.query("""
            SELECT doc_id, origin, source_file, doc_type, raw_text, findings::text AS findings,
                   allowed_groups, created_at
            FROM quarantine
            WHERE""" + DocFilter.groupClause(ctx.groups()) + """
             AND project_id = ? AND doc_id = ?
            """, MAPPER, args.toArray()).stream().findFirst();
    }

    /** Just enough of a held row to write its audit entry - deliberately NOT the raw text. */
    public record PenSummary(String docId, String findingsJson, List<String> allowedGroups) {}

    /**
     * Every held document in a project, ignoring group scoping.
     *
     * <p>The ONLY caller is the project-delete path, which must record what a cascading delete is
     * about to destroy - and it must see documents the deleting user could not read, or the audit
     * would be a partial record of a total deletion. It is safe to leave unscoped ONLY because it
     * returns no raw text and no findings excerpt beyond what the pen already masks. Never expose
     * this over HTTP, and never widen it to return {@link Held}.
     */
    public List<PenSummary> heldForAudit(long projectId) {
        return jdbc.query("""
            SELECT doc_id, findings::text AS findings, allowed_groups
            FROM quarantine
            WHERE project_id = ?
            ORDER BY doc_id
            """, (rs, n) -> new PenSummary(rs.getString("doc_id"), rs.getString("findings"),
                toList(rs.getArray("allowed_groups"))), projectId);
    }

    /** Returns rows removed: 0 when nothing was held under that id. */
    public int drop(long projectId, String docId) {
        return jdbc.update("DELETE FROM quarantine WHERE project_id = ? AND doc_id = ?",
                projectId, docId);
    }

    private static List<String> toList(java.sql.Array array) {
        try {
            return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not read allowed_groups", e);
        }
    }
}
