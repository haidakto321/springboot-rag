package com.example.springbootrag.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit test for breadcrumb rendering - no Spring context needed. */
class HeadingStyleTest {

    private static final String PATH = "# Guide > ## Setup > ### Flags";

    @Test
    void fullKeepsThePathUnchanged() {
        assertThat(HeadingStyle.render(HeadingStyle.FULL, PATH, 2)).isEqualTo(PATH);
    }

    @Test
    void embedOnlyRendersIdenticallyToFull() {
        assertThat(HeadingStyle.render(HeadingStyle.EMBED_ONLY, PATH, 2)).isEqualTo(PATH);
    }

    @Test
    void noneRendersNothing() {
        assertThat(HeadingStyle.render(HeadingStyle.NONE, PATH, 2)).isEmpty();
    }

    @Test
    void deepestKeepsOnlyTheDeepestLevels() {
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, PATH, 2))
                .isEqualTo("## Setup > ### Flags");
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, PATH, 1))
                .isEqualTo("### Flags");
    }

    @Test
    void deepestLeavesAShallowPathWhole() {
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, "# Guide", 2)).isEqualTo("# Guide");
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, "# Guide > ## Setup", 2))
                .isEqualTo("# Guide > ## Setup");
    }

    @Test
    void deepestTreatsNonPositiveLevelsAsOne() {
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, PATH, 0)).isEqualTo("### Flags");
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, PATH, -3)).isEqualTo("### Flags");
    }

    @Test
    void plainStripsTheHashMarks() {
        assertThat(HeadingStyle.render(HeadingStyle.PLAIN, PATH, 2))
                .isEqualTo("Guide > Setup > Flags");
    }

    @Test
    void nullOrBlankPathRendersNothingForEveryStyle() {
        for (HeadingStyle style : HeadingStyle.values()) {
            assertThat(HeadingStyle.render(style, null, 2)).isEmpty();
            assertThat(HeadingStyle.render(style, "   ", 2)).isEmpty();
        }
    }

    @Test
    void aHeadingContainingAGreaterThanSignIsNotSplitOnIt() {
        // MarkdownChunker joins levels with " > " (spaces included), so a bare '>' inside a
        // heading title must survive.
        String path = "# A>B > ## C";
        assertThat(HeadingStyle.render(HeadingStyle.DEEPEST, path, 1)).isEqualTo("## C");
        assertThat(HeadingStyle.render(HeadingStyle.PLAIN, path, 2)).isEqualTo("A>B > C");
    }
}
