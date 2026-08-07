package com.example.springbootrag.eval;

import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which corpus records a golden question SHOULD match, computed from its expected filter rather
 * than listed by hand.
 *
 * <p>Listing doc ids across 210 generated records would be a transcription exercise that goes stale
 * the moment the generator changes; deriving them keeps the golden file about intent. Evaluated
 * against the RAW record, independently of the indexing path under test - ground truth that reuses
 * the code being measured proves nothing.
 */
public final class RecordGroundTruth {

    private RecordGroundTruth() {}

    /** Doc ids of every record satisfying all of {@code expectedFilters} (and the docType). */
    public static List<String> matchingDocIds(List<RecordRequest> corpus, RecordGoldenEntry entry) {
        List<String> out = new ArrayList<>();
        for (RecordRequest r : corpus) {
            if (entry.expectedDocType() != null && !entry.expectedDocType().equals(r.docType())) {
                continue;
            }
            boolean all = true;
            for (Map<String, Object> condition : entry.expectedFilters()) {
                if (!matches(r, condition)) { all = false; break; }
            }
            if (all) out.add(r.docId());
        }
        return out;
    }

    static boolean matches(RecordRequest record, Map<String, Object> condition) {
        String path = String.valueOf(condition.get("path"));
        String op = String.valueOf(condition.get("op"));
        List<String> found = values(record, path);
        return switch (op) {
            case "exists" -> !found.isEmpty();
            case "eq" -> found.stream().anyMatch(v -> equalsLoose(v, condition.get("value")));
            case "in" -> {
                List<?> wanted = (List<?>) condition.getOrDefault("values", List.of());
                yield found.stream().anyMatch(v -> wanted.stream().anyMatch(w -> equalsLoose(v, w)));
            }
            case "range" -> found.stream().anyMatch(v -> inRange(v, condition));
            default -> throw new IllegalArgumentException("unknown golden op: " + op);
        };
    }

    /** Every scalar reachable at {@code path}; an array segment contributes one value per element. */
    static List<String> values(RecordRequest record, String path) {
        if (path.equals("conf.min") || path.equals("conf.avg")) {
            List<Double> confidences = new ArrayList<>();
            collectConfidences(record.record(), confidences);
            if (confidences.isEmpty()) return List.of();
            double value = path.equals("conf.min")
                    ? confidences.stream().mapToDouble(Double::doubleValue).min().orElseThrow()
                    : confidences.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            return List.of(String.valueOf(value));
        }
        String withinRecord = path.startsWith("values.") ? path.substring("values.".length()) : path;
        List<JsonNode> current = List.of(record.record());
        for (String rawSegment : withinRecord.split("\\.")) {
            String segment = rawSegment.replace("[]", "");
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                for (JsonNode candidate : unwrapAndFlatten(node)) {
                    JsonNode child = candidate.get(segment);
                    if (child != null && !child.isNull()) next.add(child);
                }
            }
            current = next;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode node : current) {
            for (JsonNode leaf : unwrapAndFlatten(node)) {
                if (!leaf.isObject() && !leaf.isArray()) out.add(leaf.asText());
            }
        }
        return out;
    }

    /** An array becomes its elements; a {@code {"value":...}} wrapper becomes the value it wraps. */
    private static List<JsonNode> unwrapAndFlatten(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(element -> out.addAll(unwrapAndFlatten(element)));
        } else if (isWrapper(node)) {
            out.addAll(unwrapAndFlatten(node.get("value")));
        } else {
            out.add(node);
        }
        return out;
    }

    /**
     * Same rule as {@code ValueWrapper.detect}: a value key AND nothing else but provenance.
     * "has a value key" alone is not enough - the contract records carry a real business field
     * called {@code value}, and treating that record as a wrapper made every other contract field
     * unreachable.
     */
    private static boolean isWrapper(JsonNode node) {
        if (!node.isObject() || !node.has("value")) return false;
        for (var it = node.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!name.equals("value") && !NOISE_KEYS.contains(name)) return false;
        }
        return true;
    }

    private static final java.util.Set<String> NOISE_KEYS = java.util.Set.of(
            "confidence", "score", "grounding", "bbox", "boundingBox", "polygon", "page",
            "pageNumber", "spans", "offsets", "source");

    private static void collectConfidences(JsonNode node, List<Double> out) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                if (e.getKey().equals("confidence") && e.getValue().isNumber()) {
                    out.add(e.getValue().doubleValue());
                } else {
                    collectConfidences(e.getValue(), out);
                }
            });
        } else if (node.isArray()) {
            node.forEach(element -> collectConfidences(element, out));
        }
    }

    private static boolean equalsLoose(String found, Object wanted) {
        if (wanted == null) return false;
        String w = String.valueOf(wanted);
        if (found.equalsIgnoreCase(w)) return true;
        Double a = asNumber(found);
        Double b = asNumber(w);
        return a != null && b != null && a.doubleValue() == b.doubleValue();
    }

    /** Numeric when both sides parse as numbers; otherwise lexicographic, which is ISO-date order. */
    private static boolean inRange(String found, Map<String, Object> condition) {
        return bound(found, condition.get("gte"), 0, true)
                && bound(found, condition.get("gt"), 0, false)
                && bound(found, condition.get("lte"), 1, true)
                && bound(found, condition.get("lt"), 1, false);
    }

    private static boolean bound(String found, Object limit, int direction, boolean inclusive) {
        if (limit == null) return true;
        Double a = asNumber(found);
        Double b = asNumber(String.valueOf(limit));
        int cmp = (a != null && b != null)
                ? Double.compare(a, b)
                : found.compareTo(String.valueOf(limit));
        if (direction == 0) return inclusive ? cmp >= 0 : cmp > 0;
        return inclusive ? cmp <= 0 : cmp < 0;
    }

    private static Double asNumber(String s) {
        try {
            return Double.valueOf(s.trim().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
