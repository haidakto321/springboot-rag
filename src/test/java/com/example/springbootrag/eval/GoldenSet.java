package com.example.springbootrag.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class GoldenSet {

    private GoldenSet() {}

    public static List<GoldenEntry> load() {
        try (InputStream in = GoldenSet.class.getResourceAsStream("/eval/golden.yaml")) {
            if (in == null) {
                throw new IllegalStateException("eval/golden.yaml not found on test classpath");
            }
            List<Map<String, String>> raw = new Yaml().load(in);
            return raw.stream()
                    .map(m -> new GoldenEntry(
                            m.get("question"),
                            m.get("expectedDocId"),
                            m.get("expectedHeadingPath")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("could not load golden set", e);
        }
    }
}
