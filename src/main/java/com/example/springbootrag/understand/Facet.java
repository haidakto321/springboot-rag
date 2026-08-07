package com.example.springbootrag.understand;

import java.util.List;

/** One filterable path that actually exists in the index, with evidence of what it holds. */
public record Facet(String docType, String path, String type,
                    List<String> samples, int distinctCount) {}
