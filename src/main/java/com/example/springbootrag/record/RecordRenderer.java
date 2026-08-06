package com.example.springbootrag.record;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns an extracted JSON record into embeddable blocks.
 *
 * <p>Works with zero configuration on purpose: the set of document types an extraction pipeline
 * emits is open, so an unseen type has to be searchable the moment it lands, not after someone
 * writes a schema for it. A {@link RenderProfile}, when one exists, only improves the result.
 */
public class RecordRenderer {

    /** {@code profile} may be null, meaning fully generic rendering. */
    public List<RenderedBlock> render(JsonNode record, RenderProfile profile) {
        List<RenderedBlock> out = new ArrayList<>();
        if (record == null || !record.isObject()) {
            return out;
        }
        ValueWrapper.Keys keys = profile == null ? ValueWrapper.Keys.DEFAULT : profile.wrapperKeys();

        Block header = new Block("");
        List<Runnable> deferred = new ArrayList<>();

        record.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode child = entry.getValue();
            if (profile != null && profile.isExcluded(name)) return;

            Optional<ValueWrapper.Unwrapped> wrapped = ValueWrapper.detect(child, keys);
            if (wrapped.isPresent()) {
                addScalar(header, name, wrapped.get().value(), wrapped.get().provenance(), profile);
                return;
            }
            if (child.isArray() && isArrayOfObjects(child)) {
                deferred.add(() -> {
                    for (int i = 0; i < child.size(); i++) {
                        Block element = new Block(name + "[" + i + "]");
                        // Parent scalars first: an element chunk alone ("SKU: A-1") is
                        // unanswerable without knowing which record it belongs to. The parent's
                        // metadata comes along too, so "ACME invoices whose line item is B-2"
                        // can be answered by the element chunk itself.
                        element.lines.addAll(header.lines);
                        inherit(element, header);
                        // The array index is dropped from the metadata path on purpose: each
                        // element is its own chunk, so there is no array left to index into, and
                        // a filter path stays the same whichever element matched.
                        fillFrom(element, child.get(i), keys, profile, name);
                        emit(out, element);
                    }
                });
                return;
            }
            if (child.isObject()) {
                deferred.add(() -> {
                    Block section = new Block(name);
                    inherit(section, header);
                    fillFrom(section, child, keys, profile, name);
                    emit(out, section);
                });
                return;
            }
            addScalar(header, name, child, Map.of(), profile);
        });

        emit(out, header);
        deferred.forEach(Runnable::run);   // header is complete before any element copies it
        return out;
    }

    /** {@code issueDate} -> "Issue date". Readable labels embed better than raw keys. */
    public static String label(String segment) {
        String spaced = segment.replace('_', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .trim()
                .toLowerCase();
        return spaced.isEmpty() ? segment
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /* ---- internals ---- */

    private static final class Block {
        final String breadcrumb;
        final List<String> lines = new ArrayList<>();
        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, Object> prov = new LinkedHashMap<>();

        Block(String breadcrumb) {
            this.breadcrumb = breadcrumb;
        }
    }

    private void fillFrom(Block block, JsonNode obj, ValueWrapper.Keys keys,
                          RenderProfile profile, String prefix) {
        obj.properties().forEach(e -> {
            String name = e.getKey();
            String path = prefix == null || prefix.isEmpty() ? name : prefix + "." + name;
            if (profile != null && profile.isExcluded(path)) return;

            JsonNode v = e.getValue();
            Optional<ValueWrapper.Unwrapped> wrapped = ValueWrapper.detect(v, keys);
            if (wrapped.isPresent()) {
                addScalar(block, path, wrapped.get().value(), wrapped.get().provenance(), profile);
            } else if (v.isObject()) {
                fillFrom(block, v, keys, profile, path);
            } else {
                addScalar(block, path, v, Map.of(), profile);
            }
        });
    }

    /**
     * A non-header block carries the record-level scalars too, so a filter on
     * {@code values.invoiceNumber} still selects a line-item chunk.
     */
    private static void inherit(Block child, Block header) {
        child.values.putAll(header.values);
        child.prov.putAll(header.prov);
    }

    /**
     * {@code path} is the dotted path within the record, and it is what a filter addresses.
     * Values and provenance are stored NESTED under that path, never as one flat dotted key:
     * Qdrant reads a dot inside a payload key as a path separator, so a flat key could never be
     * matched. The rendered label uses the last segment only.
     */
    private void addScalar(Block block, String path, JsonNode v,
                           Map<String, Object> provenance, RenderProfile profile) {
        if (v == null || v.isNull()) return;
        String rendered = v.isArray() ? joinScalars(v) : v.asText();
        if (rendered.isBlank()) return;

        if (!provenance.isEmpty()) {
            putPath(block.prov, path, provenance);
        }
        putPath(block.values, path, v.isNumber() ? v.numberValue() : rendered);
        if (profile != null && profile.isFilterOnly(path)) {
            return;   // metadata yes, embedded text no
        }
        String leaf = path.substring(path.lastIndexOf('.') + 1);
        String label = profile == null ? label(leaf) : profile.labelFor(path);
        block.lines.add(label + ": " + rendered);
    }

    /** {@code customer.name} -> {@code {"customer":{"name":value}}}, creating levels as needed. */
    @SuppressWarnings("unchecked")
    static void putPath(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = node.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                node.put(parts[i], next);
            }
            node = (Map<String, Object>) next;
        }
        node.put(parts[parts.length - 1], value);
    }

    private static String joinScalars(JsonNode array) {
        List<String> parts = new ArrayList<>();
        array.forEach(item -> {
            if (!item.isObject() && !item.isNull()) parts.add(item.asText());
        });
        return String.join(", ", parts);
    }

    private static boolean isArrayOfObjects(JsonNode array) {
        for (JsonNode item : array) {
            if (item.isObject()) return true;
        }
        return false;
    }

    private static void emit(List<RenderedBlock> out, Block b) {
        if (b.lines.isEmpty()) return;
        out.add(new RenderedBlock(String.join("\n", b.lines), b.breadcrumb, b.values, b.prov));
    }
}
