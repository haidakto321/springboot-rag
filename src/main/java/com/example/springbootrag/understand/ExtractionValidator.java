package com.example.springbootrag.understand;

import com.example.springbootrag.repository.MetadataFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Model output is validated against the catalogue, never trusted.
 *
 * <p>Everything the extractor produces is rebuilt through {@link MetadataFilter#parse}, so it can
 * only ever express what the DSL already allows - and can never reach the access-label term. A
 * hallucinated path is the expected case, not the exceptional one, so it is dropped with a reason
 * rather than raised as an error.
 */
public final class ExtractionValidator {

    /** The surviving filter plus why anything was discarded (goes into the trace). */
    public record Result(MetadataFilter filter, List<String> dropped) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExtractionValidator() {}

    public static Result validate(String modelJson, List<Facet> facets,
                                  int maxConditions, int maxValueLength) {
        List<String> dropped = new ArrayList<>();
        JsonNode root = parseLenient(modelJson);
        if (root == null || !root.isObject()) {
            return new Result(MetadataFilter.none(), List.of("model output was not JSON"));
        }

        Map<String, String> typeByPath = new HashMap<>();
        Set<String> knownDocTypes = new java.util.HashSet<>();
        for (Facet f : facets) {
            typeByPath.put(f.path(), f.type());
            if (f.docType() != null) knownDocTypes.add(f.docType());
        }

        String docType = root.hasNonNull("docType") ? root.get("docType").asText() : null;
        if (docType != null && !knownDocTypes.contains(docType)) {
            dropped.add("unknown docType '" + docType + "'");
            docType = null;
        }

        // Rebuild a clean filters array, then let MetadataFilter.parse do the real validation.
        ObjectNode clean = MAPPER.createObjectNode();
        if (docType != null) clean.put("docType", docType);
        var array = clean.putArray("filters");

        JsonNode filters = root.get("filters");
        if (filters != null && filters.isArray()) {
            for (JsonNode f : filters) {
                if (array.size() >= maxConditions) {
                    dropped.add("too many conditions, kept the first " + maxConditions);
                    break;
                }
                String path = f.hasNonNull("path") ? f.get("path").asText() : null;
                if (path == null || !typeByPath.containsKey(path)) {
                    dropped.add("unknown path '" + path + "'");
                    continue;
                }
                if (tooLong(f, maxValueLength)) {
                    dropped.add("value too long for '" + path + "'");
                    continue;
                }
                ObjectNode condition = normalizeOp(f.<ObjectNode>deepCopy());
                // The facet type decides the cast, not the model: it is derived from the data.
                condition.put("type", typeByPath.get(path));
                array.add(condition);
                if (!parses(clean)) {
                    array.remove(array.size() - 1);
                    dropped.add("malformed condition on '" + path + "'");
                }
            }
        }

        MetadataFilter filter = parses(clean) ? MetadataFilter.parse(clean.toString())
                                              : MetadataFilter.none();
        return new Result(filter, List.copyOf(dropped));
    }

    /** Comparison ops the DSL expresses as a {@code range} bound rather than as an op. */
    private static final Set<String> BARE_BOUNDS = Set.of("gt", "gte", "lt", "lte");

    /**
     * Rewrites {@code {"op":"gt","value":5000}} into {@code {"op":"range","gt":5000}}.
     *
     * <p>Measured, not guessed: given a corrected prompt, qwen3:4b returns the bound as the op on
     * every "over N" / "more than N" question. That is a reasonable reading of the DSL and it is
     * cheaper to accept it here than to keep asking a model not to. The prompt still describes the
     * canonical shape - a prompt is a request, this is the control.
     */
    private static ObjectNode normalizeOp(ObjectNode condition) {
        String op = condition.hasNonNull("op") ? condition.get("op").asText() : null;
        if (op == null || !BARE_BOUNDS.contains(op)) {
            return condition;
        }
        JsonNode bound = condition.get("value");
        if (bound == null && condition.has(op)) bound = condition.get(op);
        if (bound == null) return condition;      // nothing to move: let validation drop it
        condition.set(op, bound);
        condition.remove("value");
        condition.put("op", "range");
        return condition;
    }

    private static boolean parses(JsonNode candidate) {
        try {
            MetadataFilter.parse(candidate.toString());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean tooLong(JsonNode f, int max) {
        for (String key : List.of("value", "gte", "gt", "lte", "lt")) {
            JsonNode v = f.get(key);
            if (v != null && v.isTextual() && v.asText().length() > max) return true;
        }
        JsonNode values = f.get("values");
        if (values != null && values.isArray()) {
            for (JsonNode v : values) {
                if (v.isTextual() && v.asText().length() > max) return true;
            }
        }
        return false;
    }

    /** Models wrap JSON in prose; take the first balanced object. */
    static JsonNode parseLenient(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim();
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) {
                try {
                    return MAPPER.readTree(text.substring(start, i + 1));
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
