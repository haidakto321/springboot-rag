package com.example.springbootrag.understand;

import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.config.RouteProperties;

/**
 * Routing switched off, for unit tests of services that happen to call it.
 * Uses the real class with the feature disabled rather than a mock, so a change to
 * {@link QueryRouter} that breaks its "never touch the model when disabled" contract still shows
 * up here - the collaborators are null on purpose.
 */
public final class DisabledRouting {

    private DisabledRouting() {}

    public static QueryRouter create() {
        RouteProperties props = new RouteProperties();
        props.setEnabled(false);
        return new QueryRouter(null, props, new ChatProperties());
    }
}
