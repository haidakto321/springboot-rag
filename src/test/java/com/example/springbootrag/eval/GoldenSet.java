package com.example.springbootrag.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class GoldenSet {

    private static final String DEFAULT_RESOURCE = "/eval/golden.yaml";

    private GoldenSet() {}

    /** The self-corpus golden set (this project's own docs). */
    public static List<GoldenEntry> load() {
        return load(DEFAULT_RESOURCE);
    }

    /** Loads any golden set from the test classpath, e.g. "/eval/golden-wiki.yaml". */
    public static List<GoldenEntry> load(String resource) {
        try (InputStream in = GoldenSet.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not found on the test classpath");
            }
            List<Map<String, String>> raw = new Yaml().load(in);
            return raw.stream()
                    .map(m -> new GoldenEntry(
                            m.get("question"),
                            m.get("expectedDocId"),
                            m.get("expectedHeadingPath")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("could not load golden set " + resource, e);
        }
    }
}
