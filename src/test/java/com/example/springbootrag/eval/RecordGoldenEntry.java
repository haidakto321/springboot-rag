package com.example.springbootrag.eval;

import java.util.List;
import java.util.Map;

/**
 * One golden question. {@code expectNoFilter} and {@code expectWiden} are the two entries that keep
 * the design honest: a metric rewarding only successful extraction trains toward over-extraction,
 * which is the failure that hides answers.
 */
public record RecordGoldenEntry(String question, String expectedDocType,
                                List<Map<String, Object>> expectedFilters,
                                boolean expectNoFilter, boolean expectWiden) {}
