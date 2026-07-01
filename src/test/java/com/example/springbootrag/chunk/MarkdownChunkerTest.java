package com.example.springbootrag.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker =
            new MarkdownChunker(30, new WordWindowChunker(20, 5));

    @Test
    void splitsOnHeadingsWithBreadcrumb() {
        String md = """
                # Guide

                Intro paragraph here.

                ## Setup

                Install the tool first.

                ## Usage

                Run the command after setup.
                """;
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).headingPath()).isEqualTo("# Guide");
        assertThat(chunks.get(0).text()).startsWith("# Guide\n\n").contains("Intro paragraph here.");
        assertThat(chunks.get(1).headingPath()).isEqualTo("# Guide > ## Setup");
        assertThat(chunks.get(1).text()).contains("Install the tool first.");
        assertThat(chunks.get(2).headingPath()).isEqualTo("# Guide > ## Usage");
        assertThat(chunks).extracting(Chunk::position).containsExactly(0, 1, 2);
    }

    @Test
    void headingStackPopsOnSiblingAndParent() {
        String md = """
                # Doc

                ## A

                content a

                ### A1

                content a1

                ## B

                content b
                """;
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).extracting(Chunk::headingPath).containsExactly(
                "# Doc > ## A",
                "# Doc > ## A > ### A1",
                "# Doc > ## B"
        );
    }

    @Test
    void codeBlockStaysAtomicEvenWhenOverCap() {
        // 40+ words of code, cap is 30: must stay one piece, never word-window split
        StringBuilder code = new StringBuilder("```java\n");
        for (int i = 0; i < 45; i++) code.append("var v").append(i).append(" = ").append(i).append(";\n");
        code.append("```");
        String md = "# Code\n\n" + code;

        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("```java").contains("var v44 = 44;");
    }

    @Test
    void pipeTableStaysAtomic() {
        String md = """
                # T

                | col1 | col2 | col3 | col4 | col5 | col6 | col7 | col8 |
                |------|------|------|------|------|------|------|------|
                | a1   | a2   | a3   | a4   | a5   | a6   | a7   | a8   |
                | b1   | b2   | b3   | b4   | b5   | b6   | b7   | b8   |
                | c1   | c2   | c3   | c4   | c5   | c6   | c7   | c8   |
                """;
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("| a1").contains("| c8   |");
    }

    @Test
    void oversizedProseSectionSplitsIntoMultipleChunksWithBreadcrumbOnEach() {
        StringBuilder para = new StringBuilder();
        for (int i = 0; i < 50; i++) para.append("word").append(i).append(" ");
        String md = "# Big\n\n" + para.toString().trim();

        List<Chunk> chunks = chunker.chunk(md); // 50 words, cap 30 -> word-window fallback

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.text()).startsWith("# Big\n\n");
            assertThat(c.headingPath()).isEqualTo("# Big");
        });
    }

    @Test
    void twoSmallParagraphsPackIntoOneChunk() {
        String md = """
                # P

                first short paragraph.

                second short paragraph.
                """;
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("first short").contains("second short");
    }

    @Test
    void contentBeforeAnyHeadingHasNullHeadingPath() {
        String md = "no headings at all, plain prose.";
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isNull();
        assertThat(chunks.get(0).text()).isEqualTo("no headings at all, plain prose.");
    }

    @Test
    void headingWithNoContentProducesNoChunk() {
        String md = """
                # Empty

                ## AlsoEmpty

                ## HasContent

                real text
                """;
        List<Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isEqualTo("# Empty > ## HasContent");
    }

    @Test
    void blankInputYieldsNoChunks() {
        assertThat(chunker.chunk("   ")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
    }
}
