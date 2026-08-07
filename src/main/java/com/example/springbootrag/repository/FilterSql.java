package com.example.springbootrag.repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link MetadataFilter} into a SQL fragment that is appended INSIDE the retrieval
 * query. Never a post-filter on results: post-filtering returns fewer than topK hits and looks
 * exactly like bad recall.
 */
public final class FilterSql {

    /** A WHERE-clause tail plus its bind arguments, in placeholder order. */
    public record Fragment(String sql, List<Object> args) {
        public static Fragment empty() {
            return new Fragment("", List.of());
        }
    }

    private FilterSql() {}

    public static Fragment render(MetadataFilter filter) {
        if (filter == null || filter.isEmpty()) return Fragment.empty();

        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();

        if (filter.docType() != null && !filter.docType().isBlank()) {
            sql.append(" AND doc_type = ?");
            args.add(filter.docType());
        }
        for (MetadataFilter.Condition c : filter.conditions()) {
            String accessor = accessor(c.path());
            switch (c.op()) {
                case "eq" -> {
                    sql.append(" AND ").append(accessor).append(" = ?");
                    args.add(asText(c.value()));
                }
                case "in" -> {
                    sql.append(" AND ").append(accessor).append(" IN (")
                            .append(DocFilter.placeholders(c.values().size())).append(")");
                    for (Object v : c.values()) args.add(asText(v));
                }
                case "exists" -> sql.append(" AND ").append(accessor).append(" IS NOT NULL");
                case "range" -> {
                    String typed = cast(accessor, c.type());
                    String param = param(c.type());
                    if (c.gte() != null) { sql.append(" AND ").append(typed).append(" >= ").append(param); args.add(c.gte()); }
                    if (c.gt() != null) { sql.append(" AND ").append(typed).append(" > ").append(param); args.add(c.gt()); }
                    if (c.lte() != null) { sql.append(" AND ").append(typed).append(" <= ").append(param); args.add(c.lte()); }
                    if (c.lt() != null) { sql.append(" AND ").append(typed).append(" < ").append(param); args.add(c.lt()); }
                }
                default -> throw new IllegalArgumentException("unknown filter op: " + c.op());
            }
        }
        return new Fragment(sql.toString(), args);
    }

    /** {@code values.customer.name} -> {@code metadata #>> '{values,customer,name}'}. */
    static String accessor(String path) {
        return "metadata #>> '{" + String.join(",", segments(path)) + "}'";
    }

    /**
     * Splits a dotted path and validates every segment. Segments are interpolated into a literal
     * rather than bound, so anything outside {@code [A-Za-z0-9_-]} is rejected instead of escaped.
     * Array markers are dropped: an array element is its own chunk carrying its own scalars, so
     * there is no array left in the stored metadata to index into.
     */
    static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String raw : path.split("\\.")) {
            String seg = raw.replace("[]", "").trim();
            if (seg.isEmpty()) continue;
            if (!seg.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("illegal filter path segment: " + raw);
            }
            out.add(seg);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("empty filter path");
        }
        return out;
    }

    private static String cast(String accessor, String type) {
        return switch (type == null ? "text" : type) {
            case "number" -> "(" + accessor + ")::numeric";
            case "date" -> "(" + accessor + ")::timestamptz";
            default -> accessor;
        };
    }

    /**
     * The BOUND side needs the same cast as the column side.
     *
     * <p>Casting only the column produced `(metadata #>> '{...}')::timestamptz >= ?` with a String
     * argument, which Postgres rejects outright:
     * {@code operator does not exist: timestamp with time zone >= character varying}. It went
     * unnoticed because every filter written by hand so far compared text, and the unit test
     * asserted the generated string rather than executing it. A date range only became reachable
     * once query understanding started producing one.
     */
    private static String param(String type) {
        return switch (type == null ? "text" : type) {
            case "number" -> "?::numeric";
            case "date" -> "?::timestamptz";
            default -> "?";
        };
    }

    /**
     * {@code metadata #>> path} always yields text, so an {@code eq} or {@code in} value has to be
     * compared as text. A JSON number arriving as a Java Integer would otherwise render as
     * {@code text = integer}, which is the same class of type error as the range case above.
     */
    private static Object asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
