package com.example.springbootrag.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Structured narrowing over chunk metadata: "invoices from Q2 for customer X".
 *
 * <p>Never a substitute for the access-label predicate. A filter is a user preference and may only
 * narrow; a label is a boundary and always applies. The two AND together inside every backend
 * query.
 */
public record MetadataFilter(String docType, List<Condition> conditions) {

    public record Condition(String path, String op, Object value, List<Object> values,
                            Object gte, Object gt, Object lte, Object lt, String type) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> OPS = Set.of("eq", "in", "range", "exists");

    public static MetadataFilter none() {
        return new MetadataFilter(null, List.of());
    }

    public boolean isEmpty() {
        return (docType == null || docType.isBlank()) && conditions.isEmpty();
    }

    /**
     * Null or blank means "no filter". A malformed condition is a caller bug, not data variance,
     * so it throws - unlike an unknown path, which simply matches nothing because extraction
     * schemas differ per tenant.
     */
    public static MetadataFilter parse(String json) {
        if (json == null || json.isBlank()) return none();
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("filters is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("filters must be a JSON object");
        }
        String docType = root.hasNonNull("docType") ? root.get("docType").asText() : null;
        List<Condition> out = new ArrayList<>();
        JsonNode filters = root.get("filters");
        if (filters != null && filters.isArray()) {
            for (JsonNode f : filters) out.add(condition(f));
        }
        return new MetadataFilter(docType, List.copyOf(out));
    }

    private static Condition condition(JsonNode f) {
        String path = f.hasNonNull("path") ? f.get("path").asText() : null;
        String op = f.hasNonNull("op") ? f.get("op").asText() : null;
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("filter path is required");
        }
        if (op == null || !OPS.contains(op)) {
            throw new IllegalArgumentException("unknown filter op: " + op);
        }

        List<Object> values = new ArrayList<>();
        if (f.has("values")) f.get("values").forEach(v -> values.add(scalar(v)));
        if ("in".equals(op) && values.isEmpty()) {
            throw new IllegalArgumentException("op 'in' needs a non-empty values list");
        }
        Object gte = f.has("gte") ? scalar(f.get("gte")) : null;
        Object gt = f.has("gt") ? scalar(f.get("gt")) : null;
        Object lte = f.has("lte") ? scalar(f.get("lte")) : null;
        Object lt = f.has("lt") ? scalar(f.get("lt")) : null;
        if ("range".equals(op) && gte == null && gt == null && lte == null && lt == null) {
            throw new IllegalArgumentException("op 'range' needs at least one bound");
        }
        Object value = f.has("value") ? scalar(f.get("value")) : null;
        if ("eq".equals(op) && value == null) {
            throw new IllegalArgumentException("op 'eq' needs a value");
        }
        String type = f.hasNonNull("type") ? f.get("type").asText() : "text";
        return new Condition(path, op, value, List.copyOf(values), gte, gt, lte, lt, type);
    }

    private static Object scalar(JsonNode n) {
        if (n.isNumber()) return n.numberValue();
        if (n.isBoolean()) return n.booleanValue();
        return n.asText();
    }
}
