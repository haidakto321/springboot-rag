package com.example.springbootrag.understand;

import java.util.Locale;

/**
 * Which path can answer this question most cheaply.
 *
 * <p>{@link #SEARCH} is the fallback for everything uncertain, because it is what the system did
 * before routing existed: a router failure must degrade to today's behaviour, never to a new one.
 */
public enum Route {

    /** Greeting or small talk. Answered from a fixed string, with no retrieval. */
    CHITCHAT,

    /** "How many X" - answered by counting records, not by reading them. */
    AGGREGATE,

    /** A question about document content. The full RAG path. */
    SEARCH;

    /** Lower-case name, which is the form used in frames, traces and the golden file. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a route out of a model reply.
     *
     * <p>The router constrains the model with a JSON schema, so the expected reply is
     * {@code {"route":"aggregate"}}. The parse is deliberately strict about the alternative: a
     * reply naming MORE than one route is treated as unreadable rather than resolved by position.
     *
     * <p>That rule comes from a measured failure. Asked to answer in one word with
     * {@code think:false} and no schema, qwen3:4b replied
     * {@code "We need to classify it into exactly one route: chitchat, aggregate"} - it spent the
     * whole token budget restating the options. Taking the first name would have routed almost
     * every question to chitchat, which answers nothing. Ambiguous means unknown, and unknown means
     * the caller falls back to search.
     *
     * @return the route, or null when the reply names none or names several
     */
    public static Route parse(String reply) {
        if (reply == null) return null;
        String text = reply.toLowerCase(Locale.ROOT);
        Route found = null;
        for (Route r : values()) {
            if (text.contains(r.label())) {
                if (found != null) return null;   // two names: the model listed options, not a choice
                found = r;
            }
        }
        return found;
    }
}
