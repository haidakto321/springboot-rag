package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.graph")
public class GraphProperties {
    private boolean enabled = true;
    /** structural | semantic | both. Phase 1 ships "structural". */
    private String edges = "structural";
    private int neighborHops = 1;
    /** How many candidates to gather before reranking to topK. */
    private int candidates = 50;
    /** Blank = reuse the chat provider's default model. */
    private String extractModel = "";
    /** Entities mentioned fewer than this are ignored at query match time. */
    private int minMentions = 1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEdges() { return edges; }
    public void setEdges(String edges) { this.edges = edges; }
    public int getNeighborHops() { return neighborHops; }
    public void setNeighborHops(int neighborHops) { this.neighborHops = neighborHops; }
    public int getCandidates() { return candidates; }
    public void setCandidates(int candidates) { this.candidates = candidates; }
    public String getExtractModel() { return extractModel; }
    public void setExtractModel(String extractModel) { this.extractModel = extractModel; }
    public int getMinMentions() { return minMentions; }
    public void setMinMentions(int minMentions) { this.minMentions = minMentions; }
}
