package com.example.springbootrag.chunk;

import java.util.ArrayList;
import java.util.List;

/** Splits text into overlapping word windows. */
public class Chunker {

    private final int windowWords;
    private final int overlapWords;

    public Chunker(int windowWords, int overlapWords) {
        if (overlapWords >= windowWords) {
            throw new IllegalArgumentException("overlap must be smaller than window");
        }
        this.windowWords = windowWords;
        this.overlapWords = overlapWords;
    }

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String[] words = text.trim().split("\\s+");
        int step = windowWords - overlapWords;
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + windowWords, words.length);
            chunks.add(String.join(" ", java.util.Arrays.copyOfRange(words, start, end)));
            if (end == words.length) {
                break;
            }
        }
        return chunks;
    }
}
