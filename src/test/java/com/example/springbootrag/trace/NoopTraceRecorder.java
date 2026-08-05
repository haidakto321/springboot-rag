package com.example.springbootrag.trace;

/**
 * Tracing switched off, for unit tests of services that happen to record one.
 * Uses the real class with tracing disabled rather than a mock, so a change to
 * {@link TraceRecorder} that breaks its contract still shows up here.
 */
public final class NoopTraceRecorder {

    private NoopTraceRecorder() {}

    public static TraceRecorder create() {
        TraceProperties props = new TraceProperties();
        props.setEnabled(false);
        return new TraceRecorder(null, props);   // repo is never touched while disabled
    }
}
