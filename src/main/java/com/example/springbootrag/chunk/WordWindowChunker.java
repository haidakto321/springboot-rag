package com.example.springbootrag.chunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Splits text into overlapping word windows. Structure-blind fallback strategy. */
public class WordWindowChunker implements Chunker {

    private final int windowWords;
    private final int overlapWords;

    public WordWindowChunker(int windowWords, int overlapWords) {
        if (overlapWords >= windowWords) {
            throw new IllegalArgumentException("overlap must be smaller than window");
        }
        this.windowWords = windowWords;
        this.overlapWords = overlapWords;
    }

    @Override
    public List<Chunk> chunk(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String[] words = text.trim().split("\\s+");
        int step = windowWords - overlapWords;
        int position = 0;
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + windowWords, words.length);
            chunks.add(new Chunk(String.join(" ", Arrays.copyOfRange(words, start, end)), null, position++));
            if (end == words.length) {
                break;
            }
        }
        return chunks;
    }
}
