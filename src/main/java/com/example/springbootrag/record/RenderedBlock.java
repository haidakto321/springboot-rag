package com.example.springbootrag.record;

import java.util.Map;

/**
 * One embeddable block of a record: the text that gets a vector, the JSON path it came from
 * (stored in {@code chunks.heading_path}, so citations and the chunk viewer work unchanged), the
 * values behind that text, and the provenance stripped off those values.
 */
public record RenderedBlock(String text, String breadcrumb,
                            Map<String, Object> values, Map<String, Object> prov) {}
