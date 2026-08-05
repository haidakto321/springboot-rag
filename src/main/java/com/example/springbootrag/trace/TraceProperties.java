package com.example.springbootrag.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.trace")
public class TraceProperties {

    /** Turn tracing off entirely. Answers still work; debugging goes back to guesswork. */
    private boolean enabled = true;

    /** Rows kept per principal; older ones are pruned after each insert. */
    private int keep = 500;

    /** Answers longer than this are truncated in the trace. The trace is a lead, not an archive. */
    private int maxAnswerChars = 4000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getKeep() { return keep; }
    public void setKeep(int keep) { this.keep = keep; }
    public int getMaxAnswerChars() { return maxAnswerChars; }
    public void setMaxAnswerChars(int maxAnswerChars) { this.maxAnswerChars = maxAnswerChars; }
}
