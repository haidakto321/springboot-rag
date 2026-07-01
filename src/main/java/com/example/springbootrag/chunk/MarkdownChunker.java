package com.example.springbootrag.chunk;

import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.StringJoiner;

/**
 * Markdown-aware chunker: splits on heading boundaries, keeps code blocks and
 * pipe tables atomic, and prefixes every chunk with its heading breadcrumb
 * (e.g. "# Title > ## Setup") so retrieval keeps section context.
 * Sections over maxWords are packed block-by-block; a single over-cap prose
 * block falls back to the word-window strategy. Atomic blocks may exceed the cap.
 */
public class MarkdownChunker implements Chunker {

    private final int maxWords;
    private final WordWindowChunker fallback;
    private final Parser parser = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
    private final TextContentRenderer headingRenderer = TextContentRenderer.builder().build();

    public MarkdownChunker(int maxWords, WordWindowChunker fallback) {
        this.maxWords = maxWords;
        this.fallback = fallback;
    }

    private record Block(String text, int words, boolean atomic) {}
    private record Crumb(int level, String text) {}

    @Override
    public List<Chunk> chunk(String markdown) {
        List<Chunk> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        Node doc = parser.parse(markdown);
        String[] lines = markdown.split("\n", -1);
        Deque<Crumb> crumbs = new ArrayDeque<>();
        List<Block> section = new ArrayList<>();
        int[] position = {0};

        for (Node node = doc.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading) {
                flushSection(section, breadcrumb(crumbs), out, position);
                while (!crumbs.isEmpty() && crumbs.peekLast().level() >= heading.getLevel()) {
                    crumbs.removeLast();
                }
                String title = headingRenderer.render(heading).trim();
                crumbs.addLast(new Crumb(heading.getLevel(), "#".repeat(heading.getLevel()) + " " + title));
            } else {
                String text = sourceOf(node, lines);
                if (!text.isBlank()) {
                    boolean atomic = node instanceof FencedCodeBlock
                            || node instanceof IndentedCodeBlock
                            || text.stripLeading().startsWith("|");
                    section.add(new Block(text, countWords(text), atomic));
                }
            }
        }
        flushSection(section, breadcrumb(crumbs), out, position);
        return out;
    }

    /** Greedily packs a section's blocks into chunks of at most maxWords. */
    private void flushSection(List<Block> section, String breadcrumb, List<Chunk> out, int[] position) {
        if (section.isEmpty()) {
            return;
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWords = 0;

        for (Block block : section) {
            if (block.words() > maxWords && !block.atomic()) {
                // Over-cap prose: flush what we have, word-window split the block.
                if (currentWords > 0) {
                    pieces.add(current.toString());
                    current = new StringBuilder();
                    currentWords = 0;
                }
                for (Chunk piece : fallback.chunk(block.text())) {
                    pieces.add(piece.text());
                }
                continue;
            }
            if (currentWords > 0 && currentWords + block.words() > maxWords) {
                pieces.add(current.toString());
                current = new StringBuilder();
                currentWords = 0;
            }
            if (currentWords > 0) {
                current.append("\n\n");
            }
            current.append(block.text());
            currentWords += block.words();
        }
        if (currentWords > 0) {
            pieces.add(current.toString());
        }
        String headingPath = breadcrumb.isEmpty() ? null : breadcrumb;
        for (String piece : pieces) {
            String text = headingPath == null ? piece : headingPath + "\n\n" + piece;
            out.add(new Chunk(text, headingPath, position[0]++));
        }
        section.clear();
    }

    private static String breadcrumb(Deque<Crumb> crumbs) {
        StringJoiner joiner = new StringJoiner(" > ");
        for (Crumb crumb : crumbs) {
            joiner.add(crumb.text());
        }
        return joiner.toString();
    }

    /** Original markdown text of a block node, recovered via source spans. */
    private static String sourceOf(Node node, String[] lines) {
        var spans = node.getSourceSpans();
        if (spans.isEmpty()) {
            return "";
        }
        int first = spans.get(0).getLineIndex();
        int last = spans.get(spans.size() - 1).getLineIndex();
        return String.join("\n", Arrays.copyOfRange(lines, first, last + 1));
    }

    private static int countWords(String text) {
        return text.trim().split("\\s+").length;
    }
}
