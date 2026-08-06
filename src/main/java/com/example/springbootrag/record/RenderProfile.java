package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Optional per-(project, docType) rendering configuration. Data, not code: a tenant with a known
 * schema inserts a row and gets better labels and boundaries, a tenant with an unknown schema
 * inserts nothing and still gets a fully searchable document. That asymmetry is the whole design -
 * the set of document types an extraction pipeline emits is open.
 */
public final class RenderProfile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<String> include;
    private final Set<String> exclude;
    private final Map<String, String> labels;
    private final Set<String> filterOnly;
    private final Set<String> boundaries;
    private final ValueWrapper.Keys wrapperKeys;

    private RenderProfile(Set<String> include, Set<String> exclude, Map<String, String> labels,
                          Set<String> filterOnly, Set<String> boundaries,
                          ValueWrapper.Keys wrapperKeys) {
        this.include = include;
        this.exclude = exclude;
        this.labels = labels;
        this.filterOnly = filterOnly;
        this.boundaries = boundaries;
        this.wrapperKeys = wrapperKeys;
    }

    public static RenderProfile parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("profile must be a JSON object");
            }
            return new RenderProfile(
                    stringSet(root.get("include")),
                    stringSet(root.get("exclude")),
                    stringMap(root.get("labels")),
                    stringSet(root.get("filterOnly")),
                    stringSet(root.get("boundaries")),
                    wrapper(root.get("wrapper")));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("profile is not valid JSON", e);
        }
    }

    /** Exclude wins over include; an empty include means "everything not excluded". */
    public boolean isExcluded(String path) {
        if (matches(exclude, path)) return true;
        return !include.isEmpty() && !matches(include, path);
    }

    /** Filter-only paths land in metadata but never in embedded text. */
    public boolean isFilterOnly(String path) {
        return matches(filterOnly, path);
    }

    /** Paths that start a new chunk, overriding the generic array rule. */
    public boolean isBoundary(String path) {
        return matches(boundaries, path);
    }

    public String labelFor(String path) {
        String custom = labels.get(path);
        return custom != null ? custom : RecordRenderer.label(path);
    }

    public ValueWrapper.Keys wrapperKeys() {
        return wrapperKeys;
    }

    /** Exact match, a trailing {@code prefix.*} wildcard, or a trailing {@code prefix[]}. */
    private static boolean matches(Set<String> patterns, String path) {
        if (patterns.contains(path)) return true;
        for (String p : patterns) {
            if (p.endsWith(".*") && path.startsWith(p.substring(0, p.length() - 1))) return true;
            if (p.endsWith("[]") && path.startsWith(p.substring(0, p.length() - 2))) return true;
        }
        return false;
    }

    private static Set<String> stringSet(JsonNode n) {
        Set<String> out = new LinkedHashSet<>();
        if (n != null && n.isArray()) n.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static Map<String, String> stringMap(JsonNode n) {
        Map<String, String> out = new LinkedHashMap<>();
        if (n != null && n.isObject()) {
            n.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    private static ValueWrapper.Keys wrapper(JsonNode n) {
        if (n == null || !n.isObject()) return ValueWrapper.Keys.DEFAULT;
        Set<String> values = stringSet(n.get("valueKeys"));
        Set<String> conf = stringSet(n.get("confidenceKeys"));
        Set<String> ground = stringSet(n.get("groundingKeys"));
        // No declared value key means the profile says nothing useful about wrappers.
        if (values.isEmpty()) return ValueWrapper.Keys.DEFAULT;
        return new ValueWrapper.Keys(values,
                conf.isEmpty() ? Set.of("confidence", "score") : conf,
                ground);
    }
}
