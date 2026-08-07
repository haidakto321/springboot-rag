package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Query understanding: turning a question into a metadata filter. */
@ConfigurationProperties(prefix = "app.understand")
public class UnderstandProperties {

    /** Off restores exactly the pre-feature behaviour. */
    private boolean enabled = true;
    /** Empty means "use app.chat.model" - a separate knob so extraction can use a smaller model. */
    private String model = "";
    private int maxConditions = 4;
    private int facetSamples = 5;
    private long facetTtlSeconds = 300;
    /** Longest value the extractor may put in a filter; anything longer is dropped. */
    private int maxValueLength = 200;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMaxConditions() { return maxConditions; }
    public void setMaxConditions(int maxConditions) { this.maxConditions = maxConditions; }
    public int getFacetSamples() { return facetSamples; }
    public void setFacetSamples(int facetSamples) { this.facetSamples = facetSamples; }
    public long getFacetTtlSeconds() { return facetTtlSeconds; }
    public void setFacetTtlSeconds(long facetTtlSeconds) { this.facetTtlSeconds = facetTtlSeconds; }
    public int getMaxValueLength() { return maxValueLength; }
    public void setMaxValueLength(int maxValueLength) { this.maxValueLength = maxValueLength; }
}
