package com.example.springbootrag.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.grpc.JsonWithInt.Struct;
import io.qdrant.client.grpc.JsonWithInt.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a JSON object string into Qdrant payload values, preserving nesting.
 *
 * <p>Nesting is the point: Qdrant reads a dot in a filter key as a path separator, so a flat
 * payload key like {@code "customer.name"} could never be matched. Storing the object nested lets
 * a dotted filter path map straight onto Qdrant's own path syntax.
 */
final class JsonPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPayload() {}

    /** Top-level keys of the object become top-level payload keys (values, prov, conf). */
    static Map<String, Value> toQdrant(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            Map<String, Value> out = new LinkedHashMap<>();
            if (root != null && root.isObject()) {
                root.fields().forEachRemaining(e -> out.put(e.getKey(), node(e.getValue())));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("metadata is not valid JSON", e);
        }
    }

    private static Value node(JsonNode n) {
        if (n.isObject()) {
            Struct.Builder s = Struct.newBuilder();
            n.fields().forEachRemaining(e -> s.putFields(e.getKey(), node(e.getValue())));
            return Value.newBuilder().setStructValue(s.build()).build();
        }
        if (n.isArray()) {
            List<Value> items = new ArrayList<>();
            n.forEach(item -> items.add(node(item)));
            return io.qdrant.client.ValueFactory.list(items);
        }
        if (n.isNumber()) return io.qdrant.client.ValueFactory.value(n.doubleValue());
        if (n.isBoolean()) return io.qdrant.client.ValueFactory.value(n.booleanValue());
        if (n.isNull()) return Value.newBuilder().setNullValueValue(0).build();
        return io.qdrant.client.ValueFactory.value(n.asText());
    }
}
