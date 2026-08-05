package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rerank")
public class RerankProperties {
    /** "djl" enables the real cross-encoder; anything else uses the no-op IdentityReranker. */
    private String provider = "";
    /**
     * Must name a model DJL itself publishes to https://mlrepo.djl.ai, NOT an arbitrary
     * HuggingFace id: the {@code djl://} scheme resolves against DJL's own catalog of
     * pre-traced TorchScript builds. An id that is missing there fails with the misleading
     * message "Invalid djl URL". Heavier alternative: BAAI/bge-reranker-v2-m3.
     */
    private String model = "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1";
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
