package com.example.springbootrag.service;

import com.example.springbootrag.chunk.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test for the embed-budget safety net (no Spring context needed). */
class IngestServiceCapTest {

    private static final int MAX = 2000; // mirrors IngestService.MAX_CHUNK_CHARS

    @Test
    void shortChunksPassThroughUnchanged() {
        List<Chunk> in = List.of(
                new Chunk("hello world", "# A", 0),
                new Chunk("second block", "# A", 1));
        List<Chunk> out = IngestService.capToBudget(in);
        assertThat(out).isSameAs(in); // no copy when nothing is over budget
    }

    @Test
    void oversizedChunkIsSplitAndRenumbered() {
        // One giant block of space-separated words, well over the cap.
        String word = "lorem ";
        String giant = word.repeat(2000); // ~12000 chars
        List<Chunk> in = List.of(
                new Chunk("intro", "# A", 0),
                new Chunk(giant, "# A", 1),
                new Chunk("outro", "# A", 2));

        List<Chunk> out = IngestService.capToBudget(in);

        assertThat(out.size()).isGreaterThan(3); // giant split into several pieces
        for (Chunk c : out) {
            assertThat(c.text().length()).isLessThanOrEqualTo(MAX);
        }
        // positions are contiguous 0..n-1
        for (int i = 0; i < out.size(); i++) {
            assertThat(out.get(i).position()).isEqualTo(i);
        }
    }

    @Test
    void singleGiantTokenIsHardCut() {
        // No whitespace to break on: must still be cut to <= MAX per piece.
        String giantToken = "x".repeat(10000);
        List<Chunk> out = IngestService.capToBudget(List.of(new Chunk(giantToken, null, 0)));
        assertThat(out.size()).isGreaterThanOrEqualTo(3);
        for (Chunk c : out) {
            assertThat(c.text().length()).isLessThanOrEqualTo(MAX);
        }
    }
}
