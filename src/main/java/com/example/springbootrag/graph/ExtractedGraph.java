package com.example.springbootrag.graph;

import java.util.List;

public record ExtractedGraph(List<Entity> entities, List<Relation> relations) {
    public record Entity(String name, String type) {}
    public record Relation(String src, String rel, String dst) {}

    public static ExtractedGraph empty() {
        return new ExtractedGraph(List.of(), List.of());
    }
}
