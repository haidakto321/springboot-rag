package com.example.springbootrag.embedding;

/** Turns text into a vector. Swap implementations (Ollama now, Azure later) without touching callers. */
public interface EmbeddingProvider {
    float[] embed(String text);
    int dimension();
}
