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

    /**
     * Access-label predicate: " allowed_groups && ARRAY[?,?]::text[]". The caller appends one
     * argument per group, in iteration order.
     *
     * <p>Unlike the project and doc filters this is NEVER optional. A caller with no groups gets
     * {@code ARRAY[]::text[]}, and array overlap against an empty array is false for every row -
     * so "no groups" means "no results", not "all results". Fail closed by construction, and
     * closed as well for rows whose allowed_groups is NULL.
     */
    static String groupClause(java.util.Collection<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return " allowed_groups && ARRAY[]::text[]";
        }
        return " allowed_groups && ARRAY[" + placeholders(groups.size()) + "]::text[]";
    }
}
