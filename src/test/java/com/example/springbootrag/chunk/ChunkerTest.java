package com.example.springbootrag.chunk;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    @Test
    void splitsByWordCountWithOverlap() {
        Chunker chunker = new Chunker(5, 2); // 5 words/chunk, 2 overlap
        String text = "one two three four five six seven eight";
        List<String> chunks = chunker.chunk(text);

        assertThat(chunks).containsExactly(
                "one two three four five",
                "four five six seven eight"
        );
    }

    @Test
    void shortTextIsOneChunk() {
        Chunker chunker = new Chunker(5, 2);
        assertThat(chunker.chunk("only three words")).containsExactly("only three words");
    }

    @Test
    void blankTextYieldsNoChunks() {
        Chunker chunker = new Chunker(5, 2);
        assertThat(chunker.chunk("   ")).isEmpty();
    }
}
