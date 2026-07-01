package com.example.springbootrag.chunk;

import java.util.List;

/** Splits document text into ingestible chunks. */
public interface Chunker {
    List<Chunk> chunk(String text);
}
