package com.example.springbootrag.config;

import com.example.springbootrag.chunk.HeadingStyle;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Chunking knobs. See docs/superpowers/specs/2026-08-15-heading-breadcrumb-treatment-design.md */
@ConfigurationProperties(prefix = "app.chunk")
public class ChunkProperties {

    /**
     * FULL reproduces the pre-experiment behaviour byte for byte. Every other value changes the
     * text that was embedded, so changing this requires a full re-ingest - it is a deploy-time
     * knob, not a live toggle.
     */
    private HeadingStyle headingStyle = HeadingStyle.FULL;

    /** How many of the deepest heading levels DEEPEST keeps. Ignored by every other style. */
    private int deepestLevels = 2;

    public HeadingStyle getHeadingStyle() { return headingStyle; }
    public void setHeadingStyle(HeadingStyle headingStyle) { this.headingStyle = headingStyle; }
    public int getDeepestLevels() { return deepestLevels; }
    public void setDeepestLevels(int deepestLevels) { this.deepestLevels = deepestLevels; }
}
