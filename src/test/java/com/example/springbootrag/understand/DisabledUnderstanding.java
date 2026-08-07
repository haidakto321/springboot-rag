package com.example.springbootrag.understand;

import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.config.UnderstandProperties;

/**
 * Query understanding switched off, for unit tests of services that happen to call it.
 * Uses the real class with the feature disabled rather than a mock, so a change to
 * {@link QueryUnderstanding} that breaks its "never touch the model when disabled" contract still
 * shows up here - the collaborators are null on purpose.
 */
public final class DisabledUnderstanding {

    private DisabledUnderstanding() {}

    public static QueryUnderstanding create() {
        UnderstandProperties props = new UnderstandProperties();
        props.setEnabled(false);
        return new QueryUnderstanding(null, null, props, new ChatProperties());
    }
}
