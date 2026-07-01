package com.example.springbootrag.chunk;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WordWindowChunkerTest {

    @Test
    void splitsByWordCountWithOverlap() {
        WordWindowChunker chunker = new WordWindowChunker(5, 2); // 5 words/chunk, 2 overlap
        String text = "one two three four five six seven eight";
        List<Chunk> chunks = chunker.chunk(text);

        assertThat(chunks).extracting(Chunk::text).containsExactly(
                "one two three four five",
                "four five six seven eight"
        );
        assertThat(chunks).extracting(Chunk::position).containsExactly(0, 1);
        assertThat(chunks).extracting(Chunk::headingPath).containsOnlyNulls();
    }

    @Test
    void shortTextIsOneChunk() {
        WordWindowChunker chunker = new WordWindowChunker(5, 2);
        assertThat(chunker.chunk("only three words"))
                .extracting(Chunk::text).containsExactly("only three words");
    }

    @Test
    void blankTextYieldsNoChunks() {
        WordWindowChunker chunker = new WordWindowChunker(5, 2);
        assertThat(chunker.chunk("   ")).isEmpty();
    }

    @Test
    void overlapMustBeSmallerThanWindow() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new WordWindowChunker(5, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
