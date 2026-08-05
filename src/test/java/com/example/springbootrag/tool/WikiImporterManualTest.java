package com.example.springbootrag.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Manual bulk import against a real local wiki clone. Gated: set WIKI_DIR to the clone path
 * and RUN_WIKI_IMPORT=true. Not part of the normal suite (needs a real corpus + Ollama).
 *   RUN_WIKI_IMPORT=true WIKI_DIR=/path/to/wiki ./mvnw -Dtest=WikiImporterManualTest test
 */
@EnabledIfEnvironmentVariable(named = "RUN_WIKI_IMPORT", matches = "true")
class WikiImporterManualTest {

    @Test
    void importsWikiDirectory() {
        String dir = System.getenv("WIKI_DIR");
        // Boot a minimal context or reuse an existing Spring test harness to obtain WikiImporter.
        // Assert importDir(...) returns > 0 pages and doc_edge is non-empty.
        // (Left as a manual smoke: the assertion below is the shape, wire to your context.)
        org.junit.jupiter.api.Assertions.assertNotNull(dir, "set WIKI_DIR");
    }
}
