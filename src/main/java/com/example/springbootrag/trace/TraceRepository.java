package com.example.springbootrag.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stores and reads {@link RagTrace} rows.
 *
 * <p>Reads are scoped to one principal on purpose: a trace holds the question someone typed and
 * the documents it matched, so an unscoped trace list would leak exactly what the access filter in
 * {@code SearchContext} protects.
 */
@Repository
public class TraceRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TraceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(RagTrace t) {
        jdbc.update("""
            INSERT INTO rag_trace (request_id, ts, principal, project_ids, raw_query, condensed_query,
                                   backend, retrieved, stage_latency_ms, prompt_tokens,
                                   completion_tokens, answer, guard_reason)
            VALUES (?, ?, ?, ?::bigint[], ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            ON CONFLICT (request_id) DO NOTHING
            """,
                t.requestId(),
                t.ts() == null ? null : java.sql.Timestamp.from(t.ts()),
                t.principal(),
                toArrayLiteral(t.projectIds()),
                t.rawQuery(),
                t.condensedQuery(),
                t.backend(),
                toJson(t.retrieved()),
                toJson(t.stageLatencyMs()),
                t.promptTokens(),
                t.completionTokens(),
                t.answer(),
                t.guardReason());
    }

    /** Newest first, one principal only. */
    public List<RagTrace> recent(String principal, int limit) {
        return jdbc.query("""
            SELECT request_id, ts, principal, project_ids, raw_query, condensed_query, backend,
                   retrieved, stage_latency_ms, prompt_tokens, completion_tokens, answer, guard_reason
            FROM rag_trace WHERE principal = ? ORDER BY ts DESC, id DESC LIMIT ?
            """, mapRow(), principal, limit);
    }

    /**
     * Keeps the newest {@code keep} rows for this principal and deletes the rest. Traces are
     * debugging exhaust, not records: without a cap a laboratory quietly fills its disk. Called
     * after each insert, which is affordable at this scale and needs no scheduler.
     */
    public int prune(String principal, int keep) {
        return jdbc.update("""
            DELETE FROM rag_trace
            WHERE principal = ? AND id NOT IN (
                SELECT id FROM rag_trace WHERE principal = ? ORDER BY ts DESC, id DESC LIMIT ?)
            """, principal, principal, keep);
    }

    private RowMapper<RagTrace> mapRow() {
        return (rs, n) -> new RagTrace(
                (UUID) rs.getObject("request_id"),
                rs.getTimestamp("ts").toInstant(),
                rs.getString("principal"),
                fromLongArray(rs.getArray("project_ids")),
                rs.getString("raw_query"),
                rs.getString("condensed_query"),
                rs.getString("backend"),
                fromJson(rs.getString("retrieved"), new TypeReference<List<RagTrace.Retrieved>>() {}),
                fromJson(rs.getString("stage_latency_ms"), new TypeReference<Map<String, Long>>() {}),
                (Integer) rs.getObject("prompt_tokens"),
                (Integer) rs.getObject("completion_tokens"),
                rs.getString("answer"),
                rs.getString("guard_reason"));
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // A trace must never break the request it describes.
            return value instanceof Map ? "{}" : "[]";
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable trace JSON", e);
        }
    }

    /** Postgres array literal - the driver cannot infer a SQL type from a bare Long[]. */
    private static String toArrayLiteral(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.append('}').toString();
    }

    private static List<Long> fromLongArray(Array array) {
        if (array == null) return List.of();
        try {
            Object raw = array.getArray();
            List<Long> out = new ArrayList<>();
            for (Object o : (Object[]) raw) {
                if (o != null) out.add(((Number) o).longValue());
            }
            return out;
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }
}
