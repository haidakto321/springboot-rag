package com.example.springbootrag.repository;

import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.Range;

import java.util.ArrayList;
import java.util.List;

import static io.qdrant.client.ConditionFactory.isNull;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.range;

/**
 * The same filter in Qdrant's dialect.
 *
 * <p>Payload metadata is stored nested, so a dotted path maps straight onto Qdrant's own path
 * syntax. That is exactly why it is stored nested and not as flat dotted keys: Qdrant reads a dot
 * inside a key as a path separator, so a literal {@code "customer.name"} key could never match.
 */
public final class FilterQdrant {

    private FilterQdrant() {}

    /** Conditions that go into {@code must}. */
    public static List<Condition> conditions(MetadataFilter filter) {
        List<Condition> out = new ArrayList<>();
        if (filter == null || filter.isEmpty()) return out;

        if (filter.docType() != null && !filter.docType().isBlank()) {
            out.add(matchKeyword("doc_type", filter.docType()));
        }
        for (MetadataFilter.Condition c : filter.conditions()) {
            String key = key(c.path());
            switch (c.op()) {
                case "eq" -> out.add(matchValue(key, c.value()));
                case "in" -> {
                    Filter.Builder any = Filter.newBuilder();
                    for (Object v : c.values()) any.addShould(matchValue(key, v));
                    out.add(Condition.newBuilder().setFilter(any.build()).build());
                }
                case "exists" -> { /* handled by mustNotConditions - Qdrant only has is_null */ }
                case "range" -> out.add(range(key, numericRange(c)));
                default -> throw new IllegalArgumentException("unknown filter op: " + c.op());
            }
        }
        return out;
    }

    /**
     * Conditions that go into {@code must_not}. Qdrant offers {@code is_null}, which is the
     * inverse of {@code exists}, so "the path exists" is expressed as "not null".
     */
    public static List<Condition> mustNotConditions(MetadataFilter filter) {
        List<Condition> out = new ArrayList<>();
        if (filter == null || filter.isEmpty()) return out;
        for (MetadataFilter.Condition c : filter.conditions()) {
            if ("exists".equals(c.op())) {
                out.add(isNull(key(c.path())));
            }
        }
        return out;
    }

    private static String key(String path) {
        return String.join(".", FilterSql.segments(path));
    }

    /**
     * Qdrant's Range is numeric only. A date range therefore fails loudly here rather than
     * silently not applying - a filter that quietly does nothing is worse than an error. Storing
     * dates as epoch numbers is the follow-up if this becomes a real need.
     */
    private static Range numericRange(MetadataFilter.Condition c) {
        if ("date".equalsIgnoreCase(c.type())) {
            throw new IllegalArgumentException(
                    "date range is not supported on the qdrant backend (path: " + c.path() + ")");
        }
        Range.Builder r = Range.newBuilder();
        if (c.gte() instanceof Number n) r.setGte(n.doubleValue());
        if (c.gt() instanceof Number n) r.setGt(n.doubleValue());
        if (c.lte() instanceof Number n) r.setLte(n.doubleValue());
        if (c.lt() instanceof Number n) r.setLt(n.doubleValue());
        return r.build();
    }

    private static Condition matchValue(String key, Object v) {
        if (v instanceof Number n) return match(key, n.longValue());
        if (v instanceof Boolean b) return match(key, b);
        return matchKeyword(key, String.valueOf(v));
    }
}
