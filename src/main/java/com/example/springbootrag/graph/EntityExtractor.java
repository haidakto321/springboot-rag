package com.example.springbootrag.graph;

import com.example.springbootrag.chat.ChatProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts entities + relations from a chunk via the chat model. Best-effort:
 * returns ExtractedGraph.empty() on any model or parse failure so ingest never breaks.
 */
public class EntityExtractor {

    static final String SYSTEM = """
            Extract named entities and their relations from the text. Entity types are hints:
            service, feature, team, concept - use "other" if none fit. Respond with ONLY a JSON
            object, no prose, in this exact shape:
            {"entities":[{"name":"...","type":"..."}],
             "relations":[{"src":"...","rel":"...","dst":"..."}]}
            If there are no entities, return {"entities":[],"relations":[]}.
            """;

    private final ChatProvider chat;
    private final String model;   // reserved: a dedicated extract model; blank = provider default
    private final ObjectMapper mapper = new ObjectMapper();

    public EntityExtractor(ChatProvider chat, String model) {
        this.chat = chat;
        this.model = model;
    }

    public ExtractedGraph extract(String chunkText) {
        try {
            String raw = chat.chat(SYSTEM, chunkText);
            String json = sliceJson(raw);
            if (json == null) return ExtractedGraph.empty();
            JsonNode root = mapper.readTree(json);
            return new ExtractedGraph(readEntities(root.get("entities")),
                                      readRelations(root.get("relations")));
        } catch (Exception e) {
            return ExtractedGraph.empty();
        }
    }

    /* Extract the first {...} block so stray tokens around the JSON do not break parsing. */
    static String sliceJson(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : null;
    }

    private static List<ExtractedGraph.Entity> readEntities(JsonNode arr) {
        List<ExtractedGraph.Entity> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String name = text(n, "name");
                if (name != null && !name.isBlank()) {
                    out.add(new ExtractedGraph.Entity(name.trim(), orOther(text(n, "type"))));
                }
            }
        }
        return out;
    }

    private static List<ExtractedGraph.Relation> readRelations(JsonNode arr) {
        List<ExtractedGraph.Relation> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String src = text(n, "src"), rel = text(n, "rel"), dst = text(n, "dst");
                if (src != null && dst != null && rel != null) {
                    out.add(new ExtractedGraph.Relation(src.trim(), rel.trim(), dst.trim()));
                }
            }
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String orOther(String type) {
        return type == null || type.isBlank() ? "other" : type.trim();
    }
}
