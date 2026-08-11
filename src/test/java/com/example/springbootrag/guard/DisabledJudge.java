package com.example.springbootrag.guard;

import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.config.GuardProperties;

/**
 * The groundedness judge switched off, for unit tests of services that happen to call it.
 *
 * <p>The real class with the feature disabled rather than a mock, and with a null ChatProvider on
 * purpose: if {@link GroundednessJudge} ever stopped honouring "no call at all when disabled", the
 * NPE would surface here instead of in a production latency graph. This is also the default
 * configuration, so these tests exercise the shipped behaviour.
 */
public final class DisabledJudge {

    private DisabledJudge() {}

    public static GroundednessJudge create() {
        return new GroundednessJudge(null, new GuardProperties(), new ChatProperties());
    }
}
