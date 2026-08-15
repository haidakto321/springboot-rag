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

    // ---- Heading style experiment (see 2026-08-15-heading-breadcrumb-treatment spec) ----------
    // The tests above must keep passing untouched: that is the proof the default did not move.

    /** Three heading levels, one short paragraph - the fixture every style test below shares. */
    private static final String NESTED_MD = """
            # Guide

            ## Setup

            ### Flags

            Pass the verbose flag.
            """;

    private static List<Chunk> chunkWith(HeadingStyle style, int deepestLevels) {
        return new MarkdownChunker(30, new WordWindowChunker(20, 5), style, deepestLevels)
                .chunk(NESTED_MD);
    }

    @Test
    void defaultConstructorStillProducesTheFullBreadcrumb() {
        List<Chunk> chunks = chunker.chunk(NESTED_MD);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).startsWith("# Guide > ## Setup > ### Flags\n\n");
    }

    @Test
    void fullStyleMatchesTheDefaultConstructor() {
        assertThat(chunkWith(HeadingStyle.FULL, 2).get(0).text())
                .isEqualTo(chunker.chunk(NESTED_MD).get(0).text());
    }

    @Test
    void deepestStyleDropsTheAncestors() {
        assertThat(chunkWith(HeadingStyle.DEEPEST, 2).get(0).text())
                .startsWith("## Setup > ### Flags\n\n")
                .contains("Pass the verbose flag.");
    }

    @Test
    void plainStyleDropsTheHashMarks() {
        assertThat(chunkWith(HeadingStyle.PLAIN, 2).get(0).text())
                .startsWith("Guide > Setup > Flags\n\n")
                .contains("Pass the verbose flag.");
    }

    @Test
    void noneStyleEmitsThePieceAlone() {
        assertThat(chunkWith(HeadingStyle.NONE, 2).get(0).text())
                .isEqualTo("Pass the verbose flag.");
    }

    @Test
    void embedOnlyStyleComposesLikeFull() {
        assertThat(chunkWith(HeadingStyle.EMBED_ONLY, 2).get(0).text())
                .startsWith("# Guide > ## Setup > ### Flags\n\n");
    }

    @Test
    void headingPathStaysTheFullPathInEveryStyle() {
        for (HeadingStyle style : HeadingStyle.values()) {
            assertThat(chunkWith(style, 2).get(0).headingPath())
                    .as("headingPath must stay the full path for %s - the eval matches on it", style)
                    .isEqualTo("# Guide > ## Setup > ### Flags");
        }
    }
}
