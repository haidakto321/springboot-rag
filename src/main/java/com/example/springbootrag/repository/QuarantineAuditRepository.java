package com.example.springbootrag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Append-only history of every quarantine decision.
 *
 * <p>This table exists because {@code release} and {@code discard} both end in
 * {@code pen.drop(...)}, and the pen row is otherwise the only record that a document was ever
 * held. It carries the masked findings and NEVER the raw text - see the comment in schema.sql.
 *
 * <p>Reads are not group-scoped, because there is no read endpoint: psql is the reader for now
 * (ROADMAP). Adding one means deciding whether a doc id plus a principal is itself sensitive.
 */
@Repository
public class QuarantineAuditRepository {

    public static final String ACTION_HELD = "held";
    public static final String ACTION_RELEASE = "release";
    public static final String ACTION_DISCARD = "discard";

    /** A decision that started and has not been stamped: the row nobody finished. */
    public static final String OUTCOME_ATTEMPTED = "attempted";
    public static final String OUTCOME_OK = "ok";
    /** The system reached a decision and it failed, as opposed to nobody finishing it. */
    public static final String OUTCOME_FAILED = "failed";

    public record Entry(long id, long projectId, String docId, String action, String outcome,
                        String principal, String findingsJson, List<String> allowedGroups,
                        Instant at) {}

    private static final RowMapper<Entry> MAPPER = (rs, n) -> new Entry(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getString("doc_id"),
            rs.getString("action"),
            rs.getString("outcome"),
            rs.getString("principal"),
            rs.getString("findings"),
            toList(rs.getArray("allowed_groups")),
            rs.getTimestamp("at").toInstant());

    private final JdbcTemplate jdbc;

    public QuarantineAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the generated id, so the caller can stamp the outcome once it knows one */
    public long record(long projectId, String docId, String action, String outcome,
                       String principal, String findingsJson, List<String> allowedGroups) {
        Long id = jdbc.queryForObject("""
            INSERT INTO quarantine_audit (project_id, doc_id, action, outcome, principal,
                                          findings, allowed_groups)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::text[])
            RETURNING id
            """, Long.class, projectId, docId, action, outcome, principal,
                findingsJson == null ? "[]" : findingsJson,
                PgVectorRepository.toArrayLiteral(allowedGroups));
        if (id == null) {
            throw new IllegalStateException("audit insert returned no id for: " + docId);
        }
        return id;
    }

    /** Stamping an id that does not exist is a bug, not a no-op - it means a lost decision row. */
    public void outcome(long id, String outcome) {
        int updated = jdbc.update("UPDATE quarantine_audit SET outcome = ? WHERE id = ?", outcome, id);
        if (updated != 1) {
            throw new IllegalStateException(
                    "quarantine_audit row " + id + " not found when stamping '" + outcome + "'");
        }
    }

    /** Oldest first: the history of one document reads top to bottom. */
    public List<Entry> history(long projectId, String docId) {
        return jdbc.query("""
            SELECT id, project_id, doc_id, action, outcome, principal, findings::text AS findings,
                   allowed_groups, at
            FROM quarantine_audit
            WHERE project_id = ? AND doc_id = ?
            ORDER BY at, id
            """, MAPPER, projectId, docId);
    }

    private static List<String> toList(java.sql.Array array) {
        try {
            return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not read allowed_groups", e);
        }
    }
}
