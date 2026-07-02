package com.example.springbootrag.repository;

import java.util.List;

/** Helpers for scoping a chunk query to a subset of documents. Empty/null list = no filter. */
final class DocFilter {

    private DocFilter() {}

    static boolean active(List<String> docIds) {
        return docIds != null && !docIds.isEmpty();
    }

    /** Returns e.g. " doc_id IN (?,?,?)" (with a leading space), or "" when no filter. */
    static String inClause(List<String> docIds) {
        if (!active(docIds)) return "";
        return " doc_id IN (" + "?,".repeat(docIds.size() - 1) + "?)";
    }
}
