package com.example.springbootrag.understand;

import com.example.springbootrag.repository.MetadataFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Renders a {@link MetadataFilter} back into the shape the API accepts.
 *
 * <p>Not Jackson's default view of the record: that would emit {@code conditions} and an
 * {@code empty} flag, which is not what {@link MetadataFilter#parse} reads. A client must be able
 * to take the filter an answer reports and send it straight back as an explicit one, so the two
 * shapes have to be the same shape.
 */
public final class FilterJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FilterJson() {}

    /** Null when the filter is empty - "no filter" is an absent field, never an empty object. */
    public static JsonNode toApiShape(MetadataFilter filter) {
        if (filter == null || filter.isEmpty()) return null;
        ObjectNode root = MAPPER.createObjectNode();
        if (filter.docType() != null && !filter.docType().isBlank()) {
            root.put("docType", filter.docType());
        }
        ArrayNode filters = root.putArray("filters");
        for (MetadataFilter.Condition c : filter.conditions()) {
            ObjectNode node = filters.addObject();
            node.put("path", c.path());
            node.put("op", c.op());
            putScalar(node, "value", c.value());
            if (c.values() != null && !c.values().isEmpty()) {
                ArrayNode values = node.putArray("values");
                for (Object v : c.values()) putScalar(values, v);
            }
            putScalar(node, "gte", c.gte());
            putScalar(node, "gt", c.gt());
            putScalar(node, "lte", c.lte());
            putScalar(node, "lt", c.lt());
            if (c.type() != null) node.put("type", c.type());
        }
        return root;
    }

    /** The same thing as a string for the trace column, or null if it cannot be written. */
    public static String toApiString(MetadataFilter filter) {
        JsonNode node = toApiShape(filter);
        return node == null ? null : node.toString();
    }

    private static void putScalar(ObjectNode node, String field, Object value) {
        if (value == null) return;
        if (value instanceof Number n) node.putPOJO(field, n);
        else if (value instanceof Boolean b) node.put(field, b);
        else node.put(field, String.valueOf(value));
    }

    private static void putScalar(ArrayNode array, Object value) {
        if (value == null) return;
        if (value instanceof Number n) array.addPOJO(n);
        else if (value instanceof Boolean b) array.add(b);
        else array.add(String.valueOf(value));
    }
}
