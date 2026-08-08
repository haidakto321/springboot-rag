package com.example.springbootrag.understand;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.config.UnderstandProperties;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.security.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns a question into a metadata filter with one LLM call.
 *
 * <p>Two rules keep this safe on the answer path: it never throws, and its output is validated
 * against the facet catalogue rather than trusted. An answer that would have worked must not fail
 * because query understanding did.
 */
@Service
public class QueryUnderstanding {

    private static final Logger log = LoggerFactory.getLogger(QueryUnderstanding.class);

    /**
     * Extraction runs at temperature 0 with a fixed seed.
     *
     * <p>This is not a test convenience. Turning a question into a filter is a structured decision
     * with a right answer, and sampling makes the same question narrow the corpus differently on
     * two consecutive asks - which a user experiences as the search being broken. Measured on the
     * records eval: two identical runs moved condition recall by 0.13, enough to fail a regression
     * gate on its own noise.
     */
    static final int EXTRACTION_SEED = 42;

    /** What extraction produced, plus what it cost and what was thrown away. */
    public record Extraction(MetadataFilter filter, long latencyMs, List<String> dropped) {
        public static Extraction none() {
            return new Extraction(MetadataFilter.none(), 0L, List.of());
        }
    }

    private final ChatProvider chat;
    private final FacetCatalogue catalogue;
    private final UnderstandProperties props;
    private final ChatProperties chatProps;

    public QueryUnderstanding(ChatProvider chat, FacetCatalogue catalogue,
                              UnderstandProperties props, ChatProperties chatProps) {
        this.chat = chat;
        this.catalogue = catalogue;
        this.props = props;
        this.chatProps = chatProps;
    }

    public Extraction extract(SearchContext ctx, List<Long> projectIds, String question) {
        if (!props.isEnabled() || question == null || question.isBlank()) {
            return Extraction.none();
        }
        List<Facet> facets = catalogue.forProjects(ctx, projectIds);
        if (facets.isEmpty()) {
            return Extraction.none();   // nothing to filter on: do not pay for a model call
        }
        long start = System.nanoTime();
        try {
            String reply = chat.chat(buildPrompt(facets), question,
                    new ChatProvider.Options(model(), 0.0, EXTRACTION_SEED));
            ExtractionValidator.Result result = ExtractionValidator.validate(
                    reply, facets, props.getMaxConditions(), props.getMaxValueLength());
            return new Extraction(result.filter(), msSince(start), result.dropped());
        } catch (RuntimeException e) {
            log.warn("query understanding failed; continuing unfiltered", e);
            return new Extraction(MetadataFilter.none(), msSince(start),
                    List.of("extraction failed: " + e.getClass().getSimpleName()));
        }
    }

    /** Which model does the extraction - empty config means the answer model. */
    public String model() {
        return props.getModel() == null || props.getModel().isBlank()
                ? chatProps.getModel() : props.getModel();
    }

    /**
     * The catalogue as a prompt.
     *
     * <p>The field layout matters more than it looks. An earlier version listed each facet as
     * {@code "- invoice | values.customer | text | examples: ..."} and qwen3:4b returned
     * {@code "path": "invoice | values.customer"} - it copied the whole row, because nothing said
     * which column was the path. Every condition was then dropped as an unknown path and the
     * measured condition recall was 0.07. Each field is now named where it appears, and the example
     * pins the exact shape expected back.
     */
    static String buildPrompt(List<Facet> facets) {
        StringBuilder sb = new StringBuilder("""
                You convert a user's question into a search filter. Reply with JSON only, no prose.

                Shape:
                {"docType": "<one of the document types listed below, or omit>",
                 "filters": [{"path": "<copy one path: value from the list below, exactly>",
                              "op": "eq|in|range|exists",
                              "value": "...", "values": [...],
                              "gte": ..., "gt": ..., "lte": ..., "lt": ...}]}

                Example, for the field "path: values.customer  type: text  examples: ACME Corp":
                {"docType": "invoice",
                 "filters": [{"path": "values.customer", "op": "eq", "value": "ACME Corp"}]}

                For "X or Y" use one "in" filter with a "values" list:
                {"filters": [{"path": "values.status", "op": "in", "values": ["open", "overdue"]}]}

                Rules:
                - A "path" must be copied EXACTLY from a "path:" entry below - it always starts with
                  "values." or "conf.". Never include the document type in it. Never invent one.
                - Omit a filter you are not confident about. An empty filters list is a valid answer
                  and is better than a wrong guess.
                - Match sample values exactly when the question names one.
                - Use "range" with gte/gt/lte/lt for "over", "under", "between" and date periods.
                - Do not put the free-text part of the question into a filter; it is searched
                  separately.

                Available fields:
                """);
        // Grouped by document type so the type is stated once instead of on every row - it also
        // keeps the type out of the same line as the path, which is what caused the copying bug.
        java.util.Map<String, List<Facet>> byDocType = new java.util.LinkedHashMap<>();
        for (Facet f : facets) {
            byDocType.computeIfAbsent(f.docType() == null ? "(any)" : f.docType(),
                    k -> new java.util.ArrayList<>()).add(f);
        }
        byDocType.forEach((docType, group) -> {
            sb.append("\ndocType: ").append(docType).append('\n');
            for (Facet f : group) {
                sb.append("  path: ").append(f.path())
                  .append("   type: ").append(f.type());
                if (!f.samples().isEmpty()) {
                    sb.append("   examples: ").append(String.join(", ", f.samples()));
                }
                sb.append('\n');
            }
        });
        // The conf.* paths are computed at ingest, so their meaning is nowhere in the data and the
        // model cannot infer it from a name and two samples. Measured: asked for "high confidence"
        // it guessed conf.avg, which is defensible but not what a threshold question usually means.
        if (facets.stream().anyMatch(f -> f.path().startsWith("conf."))) {
            sb.append("""

                    About conf.*: these are the extraction pipeline's own confidence in the record,
                    from 0 to 1. conf.min is the LEAST confident field, conf.avg the average. For
                    "high confidence" or "reliable" data, prefer conf.min with a range filter -
                    a record is only as trustworthy as its weakest extracted field.
                    """);
        }
        return sb.toString();
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
