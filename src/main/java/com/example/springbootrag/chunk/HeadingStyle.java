package com.example.springbootrag.chunk;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * How a heading breadcrumb is rendered into chunk text before embedding.
 *
 * <p>Kept pure and Spring-free so every style can be tested without a context. The breadcrumb has
 * always been prepended to chunk text (MarkdownChunker), but its shape was never measured - these
 * styles exist so the eval harness can price each variant. See
 * docs/superpowers/specs/2026-08-15-heading-breadcrumb-treatment-design.md
 */
public enum HeadingStyle {
    /** Today's behaviour: the whole path, hash marks included. */
    FULL,
    /** Only the deepest N levels - the root title repeats across the whole document. */
    DEEPEST,
    /** The whole path with the hash marks stripped. */
    PLAIN,
    /** No breadcrumb at all - the honest baseline. */
    NONE,
    /** Renders like FULL; IngestService strips it from the stored text. */
    EMBED_ONLY;

    /**
     * Matches how MarkdownChunker.breadcrumb() joins levels - spaces included, so a bare '>' inside
     * a heading title survives.
     */
    private static final String SEPARATOR = " > ";
    private static final Pattern SPLIT = Pattern.compile(Pattern.quote(SEPARATOR));

    /**
     * @param headingPath the full breadcrumb, or null for content that sits before any heading
     * @param deepestLevels read only by {@link #DEEPEST}; values below 1 are treated as 1
     * @return the breadcrumb text with no trailing separator, or "" when there is nothing to render
     */
    public static String render(HeadingStyle style, String headingPath, int deepestLevels) {
        if (style == NONE || headingPath == null || headingPath.isBlank()) {
            return "";
        }
        String[] levels = SPLIT.split(headingPath, -1);
        if (style == DEEPEST) {
            int keep = Math.max(1, deepestLevels);
            if (levels.length > keep) {
                levels = Arrays.copyOfRange(levels, levels.length - keep, levels.length);
            }
        }
        if (style == PLAIN) {
            for (int i = 0; i < levels.length; i++) {
                levels[i] = stripHashMarks(levels[i]);
            }
        }
        return String.join(SEPARATOR, levels);
    }

    private static String stripHashMarks(String level) {
        int i = 0;
        while (i < level.length() && level.charAt(i) == '#') {
            i++;
        }
        return level.substring(i).strip();
    }
}
