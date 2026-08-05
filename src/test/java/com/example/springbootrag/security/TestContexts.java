package com.example.springbootrag.security;

import java.util.List;
import java.util.Set;

/**
 * Identities for tests. There is deliberately no "superuser" or filter bypass: a test that wants
 * to see a chunk must be in one of its groups, exactly like a real caller. Anything else would be
 * a back door that outlives the test suite.
 */
public final class TestContexts {

    /** Default group used by ingest and by the schema.sql backfill. */
    public static final String PUBLIC_GROUP = "public";

    /** A caller who can read everything labelled 'public' - the common case in tests. */
    public static final SearchContext PUBLIC = SearchContext.of("test-public", Set.of(PUBLIC_GROUP));

    /** A caller with no groups at all: must see nothing, anywhere. */
    public static final SearchContext NOBODY = SearchContext.of("test-nobody", Set.of());

    public static SearchContext of(String... groups) {
        return SearchContext.of("test-" + String.join("-", groups), Set.of(groups));
    }

    public static List<String> publicLabel() {
        return List.of(PUBLIC_GROUP);
    }

    private TestContexts() {}
}
