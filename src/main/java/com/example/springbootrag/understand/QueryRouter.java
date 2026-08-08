package com.example.springbootrag.understand;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.config.RouteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides which path answers a question, before any retrieval happens.
 *
 * <p>Same two rules that keep {@link QueryUnderstanding} safe on the answer path: it never throws,
 * and its output is validated rather than trusted. Every failure resolves to {@link Route#SEARCH},
 * which is what the system did before this class existed - the only fallback that cannot make the
 * system worse than it already was.
 */
@Service
public class QueryRouter {

    private static final Logger log = LoggerFactory.getLogger(QueryRouter.class);

    /**
     * Classification has a right answer, so it is not sampled.
     *
     * <p>Same reason extraction is pinned (see {@link QueryUnderstanding#EXTRACTION_SEED}): the
     * same question routing differently on two consecutive asks is experienced as the product
     * being broken, and it makes any gate fail on its own noise.
     */
    static final int ROUTER_SEED = 42;

    /**
     * Greetings and pleasantries, matched against the WHOLE message after normalisation.
     *
     * <p>Deliberately not a substring match. "thanks for the invoice policy" contains "thanks" and
     * is a document question; a looser rule would answer it with a canned hello.
     */
    private static final Set<String> GREETINGS = Set.of(
            "hi", "hello", "hey", "yo", "thanks", "thank you", "ty", "ok", "okay",
            "good morning", "good afternoon", "good evening", "bye", "goodbye");

    /**
     * The reply must be {@code {"route":"<one of the three>"}}, enforced by the model runtime.
     *
     * <p>Not a prompt request - a constraint. Measured on qwen3:4b: asked in the prompt for one
     * word with {@code think:false}, it used its whole budget restating the question and never
     * answered, on every question tried. With this schema the same model was correct on 8 of 8
     * probes at a mean of 3.4 s, against 44 s (and up to 206 s) for the same call with thinking on.
     */
    static final Map<String, Object> ROUTE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("route", Map.of(
                    "type", "string",
                    "enum", java.util.Arrays.stream(Route.values()).map(Route::label).toList())),
            "required", List.of("route"));

    /** Which route, what it cost, and where the decision came from: rule, model, or fallback. */
    public record Decision(Route route, long latencyMs, String source) {
        public static Decision rule(Route route) {
            return new Decision(route, 0L, "rule");
        }
    }

    private final ChatProvider chat;
    private final RouteProperties props;
    private final ChatProperties chatProps;

    public QueryRouter(ChatProvider chat, RouteProperties props, ChatProperties chatProps) {
        this.chat = chat;
        this.props = props;
        this.chatProps = chatProps;
    }

    /** Registers the properties without adding another @EnableConfigurationProperties elsewhere. */
    @Configuration
    @EnableConfigurationProperties(RouteProperties.class)
    static class Props {}

    public Decision route(String question) {
        if (!props.isEnabled()) {
            return Decision.rule(Route.SEARCH);
        }
        if (question == null || question.isBlank()) {
            return Decision.rule(Route.CHITCHAT);
        }
        String normalised = question.strip().toLowerCase(Locale.ROOT).replaceAll("[!.?]+$", "");
        if (GREETINGS.contains(normalised)) {
            return Decision.rule(Route.CHITCHAT);
        }
        long start = System.nanoTime();
        try {
            String reply = chat.chat(buildPrompt(), question,
                    // think:false plus a schema: the schema is what actually forces an answer out
                    // of a reasoning model, and thinking would cost more than the extraction call
                    // this is meant to skip.
                    new ChatProvider.Options(model(), 0.0, ROUTER_SEED, false,
                            props.getNumPredict(), ROUTE_SCHEMA));
            Route parsed = Route.parse(reply);
            if (parsed == null) {
                log.warn("router returned an unknown label, taking the search path: {}", reply);
                return new Decision(Route.SEARCH, msSince(start), "fallback");
            }
            return new Decision(parsed, msSince(start), "model");
        } catch (RuntimeException e) {
            log.warn("routing failed; taking the search path", e);
            return new Decision(Route.SEARCH, msSince(start), "fallback");
        }
    }

    /** Which model routes - empty config means the answer model. */
    public String model() {
        return props.getModel() == null || props.getModel().isBlank()
                ? chatProps.getModel() : props.getModel();
    }

    /**
     * The routing prompt.
     *
     * <p>Short on purpose: every token here is paid on every question, so a facet-sized prompt
     * would undo the point of routing. The examples exist for the one genuinely ambiguous case -
     * a "how many" that is really a document question - which is also the reason a model is doing
     * this instead of a keyword rule.
     *
     * <p>The prompt asks for one word; {@link #ROUTE_SCHEMA} is what enforces it. Asking was
     * measured to be insufficient on qwen3:4b.
     *
     * <p>Every clause below was added for a measured miss, not for symmetry:
     * <ul>
     *   <li>"bare phrase" - "delivery notes shipped by Speedy Freight" names documents but asks
     *       nothing, and the model called it small talk, answering a real query with a canned
     *       hello (golden set 20/21 -> 21/21).</li>
     *   <li>"a VALUE inside a document ... even when it says total" and the record-vs-value wording
     *       - a live smoke sent "what is the total on invoice INV-5575" to the aggregate route,
     *       answering a factual question with a record count (value probes 3/6 -> 5/6).</li>
     * </ul>
     *
     * <p>Examples are deliberately worded differently from any golden question, so the eval keeps
     * measuring the rule rather than a memorised answer, and a test enforces that. Controls after
     * the changes: golden 21/21, 9 held-out questions 9/9.
     *
     * <p>Known miss, accepted: "how many packages are on delivery note DN-9001" still routes to
     * aggregate. It counts things INSIDE one document, which is neither a record count nor a
     * lookup, and chasing it with a sixth example would be tuning against six hand-written probes.
     */
    static String buildPrompt() {
        return """
                Classify the user's message into exactly one route. Reply with one word, nothing else.

                chitchat  - ONLY a greeting, thanks, or a question about you. Nothing to look up.
                aggregate - counts whole records in the collection: "how many <documents> match X". The answer
                            is a number of records. NOT aggregate if the question is about one named document,
                            or about a quantity stored INSIDE documents.
                search    - anything else, including any question about what a document says AND any bare
                            phrase naming documents or their contents. A question about a VALUE inside a
                            document (an amount, a date, a name) is search even when it says "total".

                Examples:
                "hello there" -> chitchat
                "how many overdue invoices does ACME have" -> aggregate
                "how many days do I have to pay an invoice" -> search
                "what does the late payment clause say" -> search
                "list the contracts with Initech" -> search
                "purchase orders raised by Initech" -> search
                "what is the amount due on invoice INV-1234" -> search
                "how much did we bill Initech in June" -> search
                "what is the grand total of the Umbrella contract" -> search
                "how many items are on delivery note DN-1234" -> search""";
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
