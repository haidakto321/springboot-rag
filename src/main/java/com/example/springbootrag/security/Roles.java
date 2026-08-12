package com.example.springbootrag.security;

/**
 * Action permissions, as opposed to the data-visibility labels in {@link SearchContext}.
 *
 * <p>A group answers "what may this caller read". A role answers "what may this caller do". They
 * are kept apart deliberately: joining a group to read that group's documents must never also hand
 * out the right to undo a security control.
 *
 * <p>The names are constants because {@code @PreAuthorize} takes a compile-time constant string -
 * a literal in the annotation and a different literal in application.yml would drift apart with no
 * compile error and no failing test.
 */
public final class Roles {

    /** Spring Security's convention: {@code hasRole('x')} checks for the authority {@code ROLE_x}. */
    public static final String PREFIX = "ROLE_";

    /** May release a held document into the index, or discard it and its evidence. */
    public static final String QUARANTINE_RELEASE = "quarantine-release";

    private Roles() {
    }
}
