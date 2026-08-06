package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Extraction pipelines wrap each field with provenance:
 * {@code {"value":"ACME","confidence":0.82,"grounding":{"page":2,"bbox":[...]}}}.
 *
 * <p>Scores and bounding boxes must never reach the embedded text. Coordinates and confidences
 * carry no meaning for retrieval, they dilute the vector, and digit strings match other digit
 * strings. This splits a wrapper into the value (which gets embedded) and its provenance (which
 * becomes filterable metadata, and for page/bbox a deep-linkable citation).
 */
public final class ValueWrapper {

    /** Key names that identify a wrapper. A render profile may supply its own set. */
    public record Keys(Set<String> valueKeys, Set<String> confidenceKeys, Set<String> groundingKeys) {

        public static final Keys DEFAULT = new Keys(
                Set.of("value", "text", "content", "raw"),
                Set.of("confidence", "score"),
                Set.of("grounding", "bbox", "boundingBox", "polygon", "page", "pageNumber",
                        "spans", "offsets", "source"));

        boolean isNoise(String key) {
            return confidenceKeys.contains(key) || groundingKeys.contains(key);
        }
    }

    /** The embeddable value plus the provenance stripped off it. */
    public record Unwrapped(JsonNode value, Map<String, Object> provenance) {}

    private ValueWrapper() {}

    /**
     * A node is a wrapper when it is an object with EXACTLY ONE value-ish key and every other key
     * is known provenance. An unrecognised key means "not a wrapper": failing open keeps data that
     * a stricter rule would silently discard, at the cost of one noisy line in the rendered text.
     */
    public static Optional<Unwrapped> detect(JsonNode node, Keys keys) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return Optional.empty();
        }
        String valueKey = null;
        for (var it = node.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (keys.valueKeys().contains(name)) {
                if (valueKey != null) return Optional.empty();   // two value keys: ambiguous
                valueKey = name;
            } else if (!keys.isNoise(name)) {
                return Optional.empty();                         // unknown key: not a wrapper
            }
        }
        if (valueKey == null) {
            return Optional.empty();
        }
        Map<String, Object> prov = new LinkedHashMap<>();
        final String vk = valueKey;
        node.properties().forEach(e -> {
            if (!e.getKey().equals(vk)) {
                collectProvenance(e.getKey(), e.getValue(), keys, prov);
            }
        });
        return Optional.of(new Unwrapped(node.get(valueKey), prov));
    }

    /**
     * Flattens one provenance entry into normalised keys: confidence, page, bbox, span. A
     * grounding object contributes its own inner keys, so {@code {"page":2}} and
     * {@code {"grounding":{"page":2}}} both land as "page".
     */
    private static void collectProvenance(String key, JsonNode v, Keys keys, Map<String, Object> out) {
        if (keys.confidenceKeys().contains(key)) {
            if (v.isNumber()) {
                out.put("confidence", v.doubleValue());
            } else if (!v.isNull()) {
                // One tenant reporting "high" must not poison a numeric range filter.
                out.put("confidence_raw", v.asText());
            }
            return;
        }
        if (v.isObject()) {
            v.properties().forEach(e -> collectProvenance(e.getKey(), e.getValue(), keys, out));
            return;
        }
        switch (key) {
            case "page", "pageNumber" -> { if (v.isNumber()) out.put("page", v.intValue()); }
            case "bbox", "boundingBox", "polygon" -> out.put("bbox", toList(v));
            case "spans", "offsets", "source" -> out.put("span", v.isArray() ? toList(v) : v.asText());
            default -> { /* unknown key inside a grounding object: not embeddable, not filterable */ }
        }
    }

    private static List<Object> toList(JsonNode n) {
        List<Object> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(item -> out.add(item.isNumber() ? item.numberValue() : item.asText()));
        }
        return out;
    }
}
