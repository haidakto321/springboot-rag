package com.example.springbootrag.chat;

/** Generates one assistant reply from a system + user prompt pair. Ollama now, Azure swap later. */
public interface ChatProvider {
    String chat(String systemPrompt, String userPrompt);
}
