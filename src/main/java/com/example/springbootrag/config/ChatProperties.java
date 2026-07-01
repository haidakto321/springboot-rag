package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {
    /** Ollama chat model name. Substitute the model chosen in Task 5 step 1. */
    private String model = "qwen2.5:7b";
    /** How many retrieved chunks go into the ask prompt. */
    private int contextChunks = 5;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getContextChunks() { return contextChunks; }
    public void setContextChunks(int contextChunks) { this.contextChunks = contextChunks; }
}
