package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rerank")
public class RerankProperties {
    /** "djl" enables the real cross-encoder; anything else uses the no-op IdentityReranker. */
    private String provider = "";
    private String model = "BAAI/bge-reranker-base";
    /** How many hybrid candidates to fetch and feed the reranker before trimming to topK. */
    private int candidates = 50;
    private int maxLength = 512;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getCandidates() { return candidates; }
    public void setCandidates(int candidates) { this.candidates = candidates; }
    public int getMaxLength() { return maxLength; }
    public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
}
