package com.example.springbootrag.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts outbound cross-page links from Azure-wiki markdown as docIds.
 * Keeps only "](/Some/Path)" style page refs; drops "#anchor" in-page jumps,
 * ".attachments" image refs, and external "http(s)" links. The last path
 * segment is sanitized to a docId with the same rule DocumentController uses.
 */
public class WikiLinkParser {

    // Matches the target inside markdown link parens: ](target)
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)]+)\\)");

    public List<String> outboundDocIds(String markdown) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        Matcher m = LINK.matcher(markdown);
        while (m.find()) {
            String target = m.group(1).trim();
            if (target.startsWith("#")) continue;                 // in-page anchor
            if (target.startsWith("http://") || target.startsWith("https://")) continue;
            if (target.contains(".attachments")) continue;        // image/attachment
            if (!target.startsWith("/")) continue;                // only absolute wiki refs
            String pathOnly = target.split("#", 2)[0];            // strip trailing anchor
            String last = pathOnly.substring(pathOnly.lastIndexOf('/') + 1);
            if (last.isBlank()) continue;
            String docId = sanitizeDocId(last);
            if (seen.add(docId)) {
                out.add(docId);
            }
        }
        return out;
    }

    /* Mirror of DocumentController.sanitizeDocId so link targets match stored docIds. */
    static String sanitizeDocId(String segment) {
        String base = segment.endsWith(".md")
                ? segment.substring(0, segment.length() - ".md".length())
                : segment;
        return base.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
