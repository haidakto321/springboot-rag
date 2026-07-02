package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {
    /** Ollama chat model name (qwen3:8b - thinking model with reasoning disabled). */
    private String model = "qwen3:8b";
    /** How many retrieved chunks go into the ask prompt. */
    private int contextChunks = 5;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getContextChunks() { return contextChunks; }
    public void setContextChunks(int contextChunks) { this.contextChunks = contextChunks; }
}
