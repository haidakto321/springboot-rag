package com.example.springbootrag.security;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Who is asking, and what they are allowed to read.
 *
 * <p>The whole point of this type is that it is built on the SERVER from the authenticated
 * principal ({@link CurrentUser}), never from a request parameter. Client-supplied scope
 * (projectIds, docIds) may only ever NARROW the result set - it can never add a group here.
 *
 * <p>Every retrieval path takes one of these as its first argument, so "search without an
 * identity" is not expressible in the API.
 */
public record SearchContext(String principal, Set<String> groups) {

    public SearchContext {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("principal is required");
        }
        groups = groups == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(groups));
    }

    public static SearchContext of(String principal, Set<String> groups) {
        return new SearchContext(principal, groups);
    }

    /**
     * A principal with no groups reads nothing. Callers do not need to special-case this - the
     * SQL and Qdrant filters already deny an empty group set - but backends can short-circuit.
     */
    public boolean readsNothing() {
        return groups.isEmpty();
    }
}
