package com.example.springbootrag.repository;

import java.util.List;

/** Helpers for scoping a chunk query to a subset of documents or projects. Empty/null list = no filter. */
final class DocFilter {

    private DocFilter() {}

    static boolean active(List<?> ids) {
        return ids != null && !ids.isEmpty();
    }

    /** Returns e.g. "?,?,?" for n placeholders (no surrounding parens). */
    static String placeholders(int n) {
        return "?,".repeat(n - 1) + "?";
    }

    /** Returns e.g. " doc_id IN (?,?,?)" (with a leading space), or "" when no filter. */
    static String inClause(List<String> docIds) {
        if (!active(docIds)) return "";
        return " doc_id IN (" + placeholders(docIds.size()) + ")";
    }
}
