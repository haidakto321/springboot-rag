package com.example.springbootrag.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RecordGoldenSet {

    private static final String RESOURCE = "/eval/records-golden.yaml";

    private RecordGoldenSet() {}

    @SuppressWarnings("unchecked")
    public static List<RecordGoldenEntry> load() {
        try (InputStream in = RecordGoldenSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " not found on the classpath");
            List<Map<String, Object>> raw = new Yaml().load(in);
            List<RecordGoldenEntry> out = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                out.add(new RecordGoldenEntry(
                        (String) m.get("question"),
                        (String) m.get("expectedDocType"),
                        (List<Map<String, Object>>) m.getOrDefault("expectedFilters", List.of()),
                        Boolean.TRUE.equals(m.get("expectNoFilter")),
                        Boolean.TRUE.equals(m.get("expectWiden")),
                        // Omitted means search: the route every question took before routing
                        // existed, so an un-annotated golden entry keeps its old meaning.
                        (String) m.getOrDefault("expectedRoute", "search")));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("could not load " + RESOURCE, e);
        }
    }
}
