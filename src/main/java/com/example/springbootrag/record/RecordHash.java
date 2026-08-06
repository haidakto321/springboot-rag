package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Two hashes, on purpose.
 *
 * <p>{@link #ofBlocks} covers the rendered text - what actually gets embedded - and drives
 * re-embedding. {@link #ofJson} covers the raw record and drives a metadata-only refresh. A
 * re-extraction that shifts a confidence from 0.82 to 0.83 changes the second and not the first,
 * so a corpus is never re-embedded to produce byte-identical vectors.
 */
public final class RecordHash {

    private RecordHash() {}

    /** Canonical (key-sorted) hash of a raw record. */
    public static String ofJson(JsonNode node) {
        return sha256(canonical(node));
    }

    /** Hash of what gets embedded: text and breadcrumb only, never values or provenance. */
    public static String ofBlocks(List<RenderedBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (RenderedBlock b : blocks) {
            // Separators that cannot occur in a rendered label or value, so no combination
            // of breadcrumb and text can collide with another combination.
            // Length-prefixed so the encoding is injective: no combination of breadcrumb and
            // text can collide with another combination, and no separator char is reserved.
            sb.append(b.breadcrumb().length()).append(':').append(b.breadcrumb())
              .append(b.text().length()).append(':').append(b.text());
        }
        return sha256(sb.toString());
    }

    /** Object keys sorted; array order preserved, because array order is data. */
    static String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(names.get(i)).append("\":").append(canonical(node.get(names.get(i))));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(canonical(node.get(i)));
            }
            return sb.append(']').toString();
        }
        return node.isTextual() ? '"' + node.asText() + '"' : node.asText();
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
