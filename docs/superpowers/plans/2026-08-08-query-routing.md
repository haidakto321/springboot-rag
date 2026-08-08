# Query Routing and the Cheapest Correct Path - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route every answer-path question to the cheapest path that can answer it correctly - a canned reply for chit-chat, one SQL `COUNT` for aggregates, today's RAG path for everything else - and gate routing quality in the existing records eval.

**Architecture:** A new `QueryRouter` in the `understand` package classifies the question (deterministic rules first, then one short LLM call at temperature 0 with `think:false`). `AskService` and `ChatService` branch on the result. The aggregate branch reuses the existing filter extraction, counts with `RecordCountRepository` (which reuses `DocFilter.groupClause` and `FilterSql.render`, so access control and the filter DSL are not re-implemented), and answers from a code-built template so no model ever writes the number. Route reaches the client as a `route` NDJSON frame and a chip in the chat UI, and reaches storage as a `rag_trace.route` column.

**Tech Stack:** Java 21 target on Java 25 runtime, Spring Boot 3.5.6, Postgres + pgvector, Qdrant, Ollama (`qwen3:4b`), JUnit 5 + AssertJ + Mockito + MockWebServer, Testcontainers, SnakeYAML.

**Spec:** `docs/superpowers/specs/2026-08-08-query-routing-design.md`

## Global Constraints

- Build and test with `./mvnw`, never a system `mvn`.
- The router NEVER throws. Any failure returns `Route.SEARCH`, which is exactly today's behaviour.
- `app.route.enabled=false` must restore pre-feature behaviour byte for byte.
- No model output reaches SQL identifiers. Filter paths are already validated against the facet catalogue; values are bound parameters.
- Every count query carries the caller's access labels via `DocFilter.groupClause`. No second copy of that predicate.
- Aggregate never widens on empty. Zero is a legitimate count.
- Code comments in English. No Lombok. No new dependencies.
- Never run `git add` / `git commit` - the user commits. The commit step in each task is written out so the user can run it, and must never include a `Co-Authored-By: Claude` trailer.
- Keep `docs/implementation-notes.md` updated with every off-spec decision as it happens.

## Deviations from the spec, decided while planning

Three, all recorded here so a reviewer sees them as choices rather than drift:

1. **`QueryRouter.route` takes only the question**, not `(SearchContext, projectIds, question)` as the spec sketched. Routing needs nothing else, and unused parameters are dead weight that later readers assume are meaningful.
2. **No `route_latency_ms` column.** `rag_trace.stage_latency_ms` is a JSONB map whose own javadoc says "a new stage does not need a migration". Route latency goes in under the key `route`. Only `route` itself becomes a column, because that is the field worth filtering rows by.
3. **`ChatService` gains one more overload** rather than a `Signals` record refactor. The file already delegates down a chain of overloads and `ChatServiceTest` is 14 KB; a refactor here would be unrelated churn. Noted as debt: if a sixth overload is ever needed, collapse them.

---

## File Structure

**Create:**
- `src/main/java/com/example/springbootrag/understand/Route.java` - the three-value enum plus a tolerant parse.
- `src/main/java/com/example/springbootrag/understand/QueryRouter.java` - rules, one LLM call, validation, fail-open.
- `src/main/java/com/example/springbootrag/config/RouteProperties.java` - `app.route.*`.
- `src/main/java/com/example/springbootrag/repository/RecordCountRepository.java` - scoped `COUNT(DISTINCT doc_id)`.
- `src/main/java/com/example/springbootrag/service/AggregateAnswerer.java` - count + filter -> sentence.
- `src/test/java/com/example/springbootrag/understand/QueryRouterTest.java`
- `src/test/java/com/example/springbootrag/understand/QueryRouterPromptTest.java`
- `src/test/java/com/example/springbootrag/service/AggregateAnswererTest.java`
- `src/test/java/com/example/springbootrag/integration/RecordCountIntegrationTest.java`
- `src/test/java/com/example/springbootrag/integration/RoutedAnswerIntegrationTest.java`

**Modify:**
- `chat/ChatProvider.java` - `Options` gains `think` + `numPredict`.
- `chat/OllamaChatProvider.java` - forward both.
- `trace/RagTrace.java`, `trace/TraceRepository.java`, `trace/TraceRecorder.java` - carry `route`.
- `src/main/resources/schema.sql` - `rag_trace.route`.
- `service/AskService.java`, `service/ChatService.java` - branch on route.
- `web/ChatController.java` - `route` frame.
- `web/dto/AskResponse.java` - `route` field.
- `src/main/resources/application.yml` - `app.route` block.
- `src/main/resources/static/app.js`, `style.css` - route + filter chips.
- `src/test/resources/eval/records-golden.yaml` - `expectedRoute` + 6 new questions.
- `eval/RecordGoldenEntry.java`, `eval/RecordGoldenSet.java`, `eval/RecordEvalBaseline.java`, `eval/RecordEvalBaselineStore.java`, `eval/RecordEvalComparison.java`, `eval/RecordEvalComparisonTest.java`, `eval/RecordFilterEvalTest.java`.
- `docs/LEARNINGS.md`, `docs/RAG-MASTERY.md`, `docs/ARCHITECTURE.md`, `README.md`, `docs/implementation-notes.md`.

---

## Task 1: Per-call `think` and `numPredict`

The router must not cost what it saves. On `qwen3:4b` the reasoning tokens are the latency, and the non-streaming path currently hardcodes `think: true`. A one-word classification has nothing to reason about.

**Files:**
- Modify: `src/main/java/com/example/springbootrag/chat/ChatProvider.java:22-37`
- Modify: `src/main/java/com/example/springbootrag/chat/OllamaChatProvider.java:57-75`
- Test: `src/test/java/com/example/springbootrag/chat/OllamaChatProviderTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ChatProvider.Options(String model, Double temperature, Integer seed, Boolean think, Integer numPredict)` plus the existing 3-arg constructor delegating with two nulls. Used by Tasks 2 and 9.

- [ ] **Step 1: Write the failing tests**

Add to `OllamaChatProviderTest`:

```java
    @Test
    void thinkFalseIsForwardedWhenTheCallerAsksForIt() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "search"}}
                        """));

        provider.chat("system", "user",
                new ChatProvider.Options(null, 0.0, 42, false, 16));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"think\":false");
        assertThat(body).contains("\"num_predict\":16");
        assertThat(body).contains("\"temperature\":0.0");
        assertThat(body).contains("\"seed\":42");
    }

    @Test
    void defaultsAreUnchangedWhenTheCallerHasNoOpinion() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message": {"role": "assistant", "content": "hi"}}
                        """));

        provider.chat("system", "user");

        String body = server.takeRequest().getBody().readUtf8();
        // think:true stays the default for answers - reasoning must not leak into content.
        assertThat(body).contains("\"think\":true");
        assertThat(body).doesNotContain("num_predict");
        assertThat(body).doesNotContain("\"options\"");
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw test "-Dtest=OllamaChatProviderTest"`
Expected: FAIL - the 5-argument `Options` constructor does not exist (compile error).

- [ ] **Step 3: Widen `Options`**

In `ChatProvider.java`, replace the `Options` record:

```java
    /**
     * Per-call generation settings.
     *
     * <p>Every field is optional; null means "leave the provider's default alone". The default
     * implementation ignores all of them, so a provider that cannot vary settings per call stays
     * valid.
     *
     * @param model       model name, or null/blank for the configured one
     * @param temperature 0 for a deterministic structured answer, null for the provider default
     * @param seed        fixes sampling so the same prompt gives the same answer
     * @param think       null keeps the provider default (think:true). false is for calls whose
     *                    output is a fixed vocabulary, where reasoning tokens are pure latency and
     *                    chain-of-thought leaking into content cannot break the parse
     * @param numPredict  hard cap on generated tokens, null for uncapped
     */
    record Options(String model, Double temperature, Integer seed, Boolean think, Integer numPredict) {

        /** Pre-routing callers: provider defaults for think and output length. */
        public Options(String model, Double temperature, Integer seed) {
            this(model, temperature, seed, null, null);
        }
    }
```

- [ ] **Step 4: Forward both in the Ollama provider**

In `OllamaChatProvider.chatDetailed(String, String, Options)`, replace the body map construction and the generation block:

```java
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of(
                "model", model,
                "stream", false,
                // think:true unless the caller explicitly opts out - see the Options javadoc.
                "think", options.think() == null ? Boolean.TRUE : options.think(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt))));
        // Ollama takes generation settings under "options"; omitted entirely when the caller has no
        // opinion, so the model's own defaults still apply to ordinary answers.
        Map<String, Object> generation = new java.util.LinkedHashMap<>();
        if (options.temperature() != null) generation.put("temperature", options.temperature());
        if (options.seed() != null) generation.put("seed", options.seed());
        if (options.numPredict() != null) generation.put("num_predict", options.numPredict());
        if (!generation.isEmpty()) body.put("options", generation);
```

Also update the 3-arg default call:

```java
    @Override
    public ChatReply chatDetailed(String systemPrompt, String userPrompt) {
        return chatDetailed(systemPrompt, userPrompt, new Options(null, null, null));
    }
```

(unchanged - the 3-arg constructor now fills the two new nulls.)

- [ ] **Step 5: Run the tests**

Run: `./mvnw test "-Dtest=OllamaChatProviderTest"`
Expected: PASS, all tests in the class.

- [ ] **Step 6: Run the full offline suite**

Run: `./mvnw test`
Expected: PASS - 363 tests, 0 failures, 3 skipped (the manual DJL tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/springbootrag/chat/ChatProvider.java \
        src/main/java/com/example/springbootrag/chat/OllamaChatProvider.java \
        src/test/java/com/example/springbootrag/chat/OllamaChatProviderTest.java
git commit -m "feat: let a caller turn off thinking and cap output per call"
```

---

## Task 2: The router

**Files:**
- Create: `src/main/java/com/example/springbootrag/understand/Route.java`
- Create: `src/main/java/com/example/springbootrag/config/RouteProperties.java`
- Create: `src/main/java/com/example/springbootrag/understand/QueryRouter.java`
- Create: `src/test/java/com/example/springbootrag/understand/QueryRouterTest.java`
- Create: `src/test/java/com/example/springbootrag/understand/QueryRouterPromptTest.java`
- Modify: `src/main/resources/application.yml:46` (after the `understand:` block)

**Interfaces:**
- Consumes: `ChatProvider.Options` (Task 1), `ChatProperties.getModel()`.
- Produces:
  - `enum Route { CHITCHAT, AGGREGATE, SEARCH }` with `static Route parse(String reply)`.
  - `QueryRouter.Decision(Route route, long latencyMs, String source)` - `source` is `"rule"`, `"model"` or `"fallback"`.
  - `Decision QueryRouter.route(String question)`.
  - `String QueryRouter.model()`.
  - `static String QueryRouter.buildPrompt()` (package-visible, pinned by the prompt test).

- [ ] **Step 1: Write the failing router tests**

Create `src/test/java/com/example/springbootrag/understand/QueryRouterTest.java`:

```java
package com.example.springbootrag.understand;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.config.RouteProperties;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRouterTest {

    /** Returns whatever the test tells it to, and records how it was called. */
    private static class StubChat implements ChatProvider {
        String lastSystem;
        String lastUser;
        Options lastOptions;
        int calls;
        Function<String, String> reply = q -> "search";
        RuntimeException boom;

        @Override public String chat(String systemPrompt, String userPrompt) {
            return chat(systemPrompt, userPrompt, new Options(null, null, null));
        }

        @Override public String chat(String systemPrompt, String userPrompt, Options options) {
            calls++;
            lastSystem = systemPrompt;
            lastUser = userPrompt;
            lastOptions = options;
            if (boom != null) throw boom;
            return reply.apply(userPrompt);
        }
    }

    private static QueryRouter router(StubChat chat, RouteProperties props) {
        ChatProperties chatProps = new ChatProperties();
        chatProps.setModel("qwen3:4b");
        return new QueryRouter(chat, props, chatProps);
    }

    private static RouteProperties enabled() {
        RouteProperties p = new RouteProperties();
        p.setEnabled(true);
        return p;
    }

    @Test
    void aGreetingIsRoutedByRuleWithoutCallingTheModel() {
        StubChat chat = new StubChat();

        QueryRouter.Decision d = router(chat, enabled()).route("hi");

        assertThat(d.route()).isEqualTo(Route.CHITCHAT);
        assertThat(d.source()).isEqualTo("rule");
        assertThat(chat.calls).isZero();
    }

    @Test
    void blankInputIsChitchatByRule() {
        StubChat chat = new StubChat();

        assertThat(router(chat, enabled()).route("   ").route()).isEqualTo(Route.CHITCHAT);
        assertThat(chat.calls).isZero();
    }

    @Test
    void aCountingQuestionIsRoutedByTheModelNotByAKeyword() {
        // No "how many" rule exists on purpose: "how many days do I have to pay" is a document
        // question with the same keyword. The model decides, and here it says aggregate.
        StubChat chat = new StubChat();
        chat.reply = q -> "aggregate";

        QueryRouter.Decision d = router(chat, enabled()).route("how many overdue invoices for ACME");

        assertThat(d.route()).isEqualTo(Route.AGGREGATE);
        assertThat(d.source()).isEqualTo("model");
        assertThat(chat.calls).isEqualTo(1);
    }

    @Test
    void theRouterCallDisablesThinkingAndCapsOutput() {
        StubChat chat = new StubChat();

        router(chat, enabled()).route("how many invoices are there");

        assertThat(chat.lastOptions.think()).isFalse();
        assertThat(chat.lastOptions.numPredict()).isEqualTo(32);
        assertThat(chat.lastOptions.temperature()).isEqualTo(0.0);
        assertThat(chat.lastOptions.seed()).isEqualTo(QueryRouter.ROUTER_SEED);
        assertThat(chat.lastOptions.model()).isEqualTo("qwen3:4b");
    }

    @Test
    void aLabelBuriedInLeakedReasoningIsStillRead() {
        // think:false makes qwen3 dump tag-less chain-of-thought into content (LEARNINGS 12).
        // The parse is a keyword scan precisely so that is harmless.
        StubChat chat = new StubChat();
        chat.reply = q -> "Okay, the user wants a count, so this is AGGREGATE.";

        assertThat(router(chat, enabled()).route("how many contracts").route())
                .isEqualTo(Route.AGGREGATE);
    }

    @Test
    void anUnknownLabelFallsBackToSearch() {
        StubChat chat = new StubChat();
        chat.reply = q -> "banana";

        QueryRouter.Decision d = router(chat, enabled()).route("what does the policy say");

        assertThat(d.route()).isEqualTo(Route.SEARCH);
        assertThat(d.source()).isEqualTo("fallback");
    }

    @Test
    void aFailingModelFallsBackToSearchAndNeverThrows() {
        StubChat chat = new StubChat();
        chat.boom = new IllegalStateException("ollama down");

        QueryRouter.Decision d = router(chat, enabled()).route("what does the policy say");

        assertThat(d.route()).isEqualTo(Route.SEARCH);
        assertThat(d.source()).isEqualTo("fallback");
    }

    @Test
    void disabledMeansEverythingIsSearchAndNothingIsCalled() {
        StubChat chat = new StubChat();
        RouteProperties off = new RouteProperties();
        off.setEnabled(false);

        QueryRouter.Decision d = router(chat, off).route("hi");

        assertThat(d.route()).isEqualTo(Route.SEARCH);
        assertThat(d.source()).isEqualTo("rule");
        assertThat(chat.calls).isZero();
    }

    @Test
    void anExplicitRouterModelOverridesTheChatModel() {
        StubChat chat = new StubChat();
        RouteProperties props = enabled();
        props.setModel("qwen3:1.7b");

        router(chat, props).route("how many invoices");

        assertThat(chat.lastOptions.model()).isEqualTo("qwen3:1.7b");
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw test "-Dtest=QueryRouterTest"`
Expected: FAIL - `Route`, `RouteProperties` and `QueryRouter` do not exist (compile error).

- [ ] **Step 3: Write `Route`**

Create `src/main/java/com/example/springbootrag/understand/Route.java`:

```java
package com.example.springbootrag.understand;

import java.util.Locale;

/**
 * Which path can answer this question most cheaply.
 *
 * <p>{@link #SEARCH} is the fallback for everything uncertain, because it is what the system did
 * before routing existed: a router failure must degrade to today's behaviour, never to a new one.
 */
public enum Route {

    /** Greeting or small talk. Answered from a fixed string, with no retrieval. */
    CHITCHAT,

    /** "How many X" - answered by counting records, not by reading them. */
    AGGREGATE,

    /** A question about document content. The full RAG path. */
    SEARCH;

    /**
     * Reads a route out of a model reply.
     *
     * <p>A keyword scan rather than an equality check: the router runs with {@code think:false},
     * and qwen3 then leaks chain-of-thought into content, so the label often arrives inside a
     * sentence. The first recognised keyword wins; anything unrecognised is the caller's problem
     * to turn into SEARCH.
     *
     * @return the route, or null when the reply names none
     */
    public static Route parse(String reply) {
        if (reply == null) return null;
        String text = reply.toLowerCase(Locale.ROOT);
        int best = Integer.MAX_VALUE;
        Route found = null;
        for (Route r : values()) {
            int at = text.indexOf(r.name().toLowerCase(Locale.ROOT));
            if (at >= 0 && at < best) {
                best = at;
                found = r;
            }
        }
        return found;
    }
}
```

- [ ] **Step 4: Write `RouteProperties`**

Create `src/main/java/com/example/springbootrag/config/RouteProperties.java`:

```java
package com.example.springbootrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Query routing: deciding which path answers a question. */
@ConfigurationProperties(prefix = "app.route")
public class RouteProperties {

    /** Off restores exactly the pre-feature behaviour: every question takes the RAG path. */
    private boolean enabled = true;
    /** Empty means "use app.chat.model" - the model-tiering knob, deliberately unused for now. */
    private String model = "";
    /** Hard cap on router output. The answer is one word; anything longer is leaked reasoning. */
    private int numPredict = 32;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getNumPredict() { return numPredict; }
    public void setNumPredict(int numPredict) { this.numPredict = numPredict; }
}
```

- [ ] **Step 5: Write `QueryRouter`**

Create `src/main/java/com/example/springbootrag/understand/QueryRouter.java`:

```java
package com.example.springbootrag.understand;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.config.ChatProperties;
import com.example.springbootrag.config.RouteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * Decides which path answers a question, before any retrieval happens.
 *
 * <p>Same two rules that keep {@link QueryUnderstanding} safe on the answer path: it never throws,
 * and its output is validated rather than trusted. Every failure resolves to {@link Route#SEARCH},
 * which is what the system did before this class existed.
 */
@Service
public class QueryRouter {

    private static final Logger log = LoggerFactory.getLogger(QueryRouter.class);

    /** Classification has a right answer; sampling it makes the same question route differently. */
    static final int ROUTER_SEED = 42;

    /**
     * Greetings and pleasantries, matched whole-string after normalisation.
     *
     * <p>Kept to exact matches on purpose. "thanks for the invoice policy" contains "thanks" and is
     * a document question; a rule that fires on a substring would answer it with "You're welcome".
     */
    private static final Set<String> GREETINGS = Set.of(
            "hi", "hello", "hey", "yo", "thanks", "thank you", "ty", "ok", "okay",
            "good morning", "good afternoon", "good evening", "bye", "goodbye");

    /** What each route costs and where the decision came from. */
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
                    // think:false - the answer is one word from a closed set, so reasoning tokens
                    // are pure latency, and leaked chain-of-thought cannot break a keyword scan.
                    new ChatProvider.Options(model(), 0.0, ROUTER_SEED, false, props.getNumPredict()));
            Route parsed = Route.parse(reply);
            if (parsed == null) {
                log.warn("router returned an unknown label, falling back to search: {}", reply);
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
     * <p>Short on purpose: every token here is paid on every question. The examples exist because
     * the one genuinely ambiguous case - a "how many" that is really a document question - cannot
     * be described in a rule, which is the whole reason a model is doing this at all.
     */
    static String buildPrompt() {
        return """
                Classify the user's message into exactly one route. Reply with one word, nothing else.

                chitchat  - a greeting, thanks, or small talk. Nothing to look up.
                aggregate - asks HOW MANY records/documents there are. The answer is a number.
                search    - anything else, including any question about what a document says.

                Examples:
                "hello there" -> chitchat
                "how many overdue invoices does ACME have" -> aggregate
                "how many days do I have to pay an invoice" -> search
                "what does the late payment clause say" -> search
                "list the contracts with Initech" -> search""";
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
```

- [ ] **Step 6: Run the router tests**

Run: `./mvnw test "-Dtest=QueryRouterTest"`
Expected: PASS, 9 tests.

- [ ] **Step 7: Pin the prompt**

The 0.07-condition-recall bug was a prompt layout no test looked at. Create `src/test/java/com/example/springbootrag/understand/QueryRouterPromptTest.java`:

```java
package com.example.springbootrag.understand;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt is the interface to the model, so it is tested like one. A layout change that reads
 * as harmless is exactly how condition recall once fell from 0.73 to 0.07 with every unit test
 * still green.
 */
class QueryRouterPromptTest {

    @Test
    void everyRouteNameAppearsExactlyAsTheParserExpectsIt() {
        String prompt = QueryRouter.buildPrompt();

        for (Route route : Route.values()) {
            String label = route.name().toLowerCase(java.util.Locale.ROOT);
            assertThat(prompt).as("route %s must be named in the prompt", label).contains(label);
            assertThat(Route.parse(label)).isEqualTo(route);
        }
    }

    @Test
    void theAmbiguousCountingExampleIsPresent() {
        // Without this example the model reads "how many" as a counting keyword and misroutes a
        // payment-terms question into an aggregate, which answers a content question with a number.
        assertThat(QueryRouter.buildPrompt())
                .contains("how many days do I have to pay an invoice\" -> search");
    }

    @Test
    void thePromptAsksForOneWordOnly() {
        assertThat(QueryRouter.buildPrompt()).contains("one word");
    }

    @Test
    void thePromptStaysShortEnoughToPayForOnEveryQuestion() {
        // A router prompt is paid on every single question; a facet-sized prompt here would undo
        // the point of routing.
        assertThat(QueryRouter.buildPrompt().length()).isLessThan(1200);
    }
}
```

- [ ] **Step 8: Run the prompt test**

Run: `./mvnw test "-Dtest=QueryRouterPromptTest"`
Expected: PASS, 4 tests.

- [ ] **Step 9: Add the config block**

In `src/main/resources/application.yml`, directly after the `understand:` block (which ends with `max-value-length` / `facet-ttl-seconds`), add:

```yaml
  route:
    enabled: true
    model: ""              # empty = app.chat.model. Point at a small model to tier (see RAG-MASTERY 8)
    num-predict: 32        # the reply is one word; anything longer is leaked reasoning
```

- [ ] **Step 10: Run the full suite**

Run: `./mvnw test`
Expected: PASS - 376 tests (363 + 9 + 4), 0 failures, 3 skipped.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/example/springbootrag/understand/Route.java \
        src/main/java/com/example/springbootrag/understand/QueryRouter.java \
        src/main/java/com/example/springbootrag/config/RouteProperties.java \
        src/test/java/com/example/springbootrag/understand/QueryRouterTest.java \
        src/test/java/com/example/springbootrag/understand/QueryRouterPromptTest.java \
        src/main/resources/application.yml
git commit -m "feat: route a question before spending an LLM call on it"
```

---

## Task 3: Counting records under the caller's access labels

**Files:**
- Create: `src/main/java/com/example/springbootrag/repository/RecordCountRepository.java`
- Create: `src/test/java/com/example/springbootrag/integration/RecordCountIntegrationTest.java`

**Interfaces:**
- Consumes: `DocFilter.groupClause` / `DocFilter.placeholders` / `DocFilter.active` (package-private, same package), `FilterSql.render`, `MetadataFilter`, `SearchContext`.
- Produces: `long RecordCountRepository.count(SearchContext ctx, List<Long> projectIds, MetadataFilter filter)`.

- [ ] **Step 1: Write the failing integration test**

Create `src/test/java/com/example/springbootrag/integration/RecordCountIntegrationTest.java`:

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.MetadataFilter;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.repository.RecordCountRepository;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A count that ignores access labels leaks the existence of documents the caller may not read -
 * "you have 40 invoices" is information even when none of them can be opened. So the predicate is
 * the same one retrieval uses, and this test proves it on a real database.
 */
@SpringBootTest(properties = {"app.graph.edges=structural", "app.understand.facet-ttl-seconds=0"})
@Testcontainers
class RecordCountIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ragdb").withUsername("rag").withPassword("rag");

    @Container
    static QdrantContainer qdrant =
            new QdrantContainer(DockerImageName.parse("qdrant/qdrant:v1.9.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.qdrant.host", qdrant::getHost);
        registry.add("app.qdrant.port", qdrant::getGrpcPort);
    }

    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    float[] v = new float[768];
                    v[0] = 1f;
                    return v;
                }

                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired RecordIngestService recordIngest;
    @Autowired ProjectRepository projectRepository;
    @Autowired RecordCountRepository counts;

    private static Long projectId;
    private static Long otherProjectId;

    @BeforeEach
    void seedOnce() throws Exception {
        if (projectId != null) return;
        long id = projectRepository.create("count-test", null);
        // Two ACME invoices, one GLOBEX, and one ACME invoice only 'finance' may read.
        recordIngest.ingest(id, record("INV-1", "invoice", "ACME Corp", "open", null));
        recordIngest.ingest(id, record("INV-2", "invoice", "ACME Corp", "overdue", null));
        recordIngest.ingest(id, record("INV-3", "invoice", "GLOBEX Ltd", "open", null));
        recordIngest.ingest(id, record("INV-4", "invoice", "ACME Corp", "open", List.of("finance")));
        long other = projectRepository.create("count-test-other", null);
        recordIngest.ingest(other, record("INV-9", "invoice", "ACME Corp", "open", null));
        projectId = id;
        otherProjectId = other;
    }

    private static RecordRequest record(String docId, String docType, String customer,
                                        String status, List<String> groups) throws Exception {
        String json = """
                {"invoiceNumber":"%s","status":"%s","total":1000.5,
                 "customer":{"value":"%s","confidence":0.9},
                 "notes":"a longer body so the record renders several chunks, which is the point: a
                          count must count records, not chunks, and this text exists to make the
                          difference visible if DISTINCT is ever dropped."}
                """.formatted(docId, status, customer);
        return new RecordRequest(docId, docType, M.readTree(json), null, groups, null);
    }

    @Test
    void countsRecordsNotChunks() {
        long n = counts.count(TestContexts.PUBLIC, List.of(projectId), MetadataFilter.none());

        // 4 ingested, one of which the public caller may not read.
        assertThat(n).isEqualTo(3);
    }

    @Test
    void appliesTheMetadataFilter() {
        MetadataFilter acme = MetadataFilter.parse("""
                {"docType":"invoice","filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

        assertThat(counts.count(TestContexts.PUBLIC, List.of(projectId), acme)).isEqualTo(2);
    }

    @Test
    void aRecordTheCallerMayNotReadIsNotEvenCounted() {
        SearchContext finance = SearchContext.of("finance-user",
                Set.of(TestContexts.PUBLIC_GROUP, "finance"));
        MetadataFilter acme = MetadataFilter.parse("""
                {"docType":"invoice","filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

        assertThat(counts.count(finance, List.of(projectId), acme)).isEqualTo(3);
        assertThat(counts.count(TestContexts.NOBODY, List.of(projectId), acme)).isZero();
    }

    @Test
    void respectsProjectScope() {
        MetadataFilter acme = MetadataFilter.parse("""
                {"filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

        assertThat(counts.count(TestContexts.PUBLIC, List.of(otherProjectId), acme)).isEqualTo(1);
        assertThat(counts.count(TestContexts.PUBLIC, List.of(projectId, otherProjectId), acme))
                .isEqualTo(3);
    }

    @Test
    void anEmptyProjectScopeCountsEverythingReadable() {
        assertThat(counts.count(TestContexts.PUBLIC, List.of(), MetadataFilter.none()))
                .isGreaterThanOrEqualTo(4);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw test "-Dtest=RecordCountIntegrationTest"`
Expected: FAIL - `RecordCountRepository` does not exist (compile error).

- [ ] **Step 3: Write the repository**

Create `src/main/java/com/example/springbootrag/repository/RecordCountRepository.java`:

```java
package com.example.springbootrag.repository;

import com.example.springbootrag.security.SearchContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * How many records match, without reading any of them.
 *
 * <p>The whole value of this class is that it answers a counting question with a count instead of
 * with ten retrieved chunks and a model guessing from them. It reuses {@link DocFilter} and
 * {@link FilterSql} rather than writing its own predicates: an access-control clause that exists in
 * two places is an access-control clause that will diverge.
 */
@Repository
public class RecordCountRepository {

    private final JdbcTemplate jdbc;

    public RecordCountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Distinct documents the caller may read that match {@code filter}.
     *
     * <p>DISTINCT doc_id, because one record renders to several chunks and "how many invoices" is
     * a question about records. Empty {@code projectIds} means every project the caller may read.
     */
    public long count(SearchContext ctx, List<Long> projectIds, MetadataFilter filter) {
        String projectClause = DocFilter.active(projectIds)
                ? " AND project_id IN (" + DocFilter.placeholders(projectIds.size()) + ")"
                : "";
        FilterSql.Fragment meta = FilterSql.render(filter);
        List<Object> args = new ArrayList<>(ctx.groups());
        if (DocFilter.active(projectIds)) args.addAll(projectIds);
        args.addAll(meta.args());
        Long n = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT doc_id) FROM chunks WHERE"
                        + DocFilter.groupClause(ctx.groups()) + projectClause + meta.sql(),
                Long.class, args.toArray());
        return n == null ? 0L : n;
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./mvnw test "-Dtest=RecordCountIntegrationTest"`
Expected: PASS, 5 tests. Docker must be running (`docker compose up -d` if `Connection to localhost:5432 refused` appears).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/springbootrag/repository/RecordCountRepository.java \
        src/test/java/com/example/springbootrag/integration/RecordCountIntegrationTest.java
git commit -m "feat: count matching records under the caller's access labels"
```

---

## Task 4: The aggregate answer, and route in the trace

**Files:**
- Create: `src/main/java/com/example/springbootrag/service/AggregateAnswerer.java`
- Create: `src/test/java/com/example/springbootrag/service/AggregateAnswererTest.java`
- Modify: `src/main/resources/schema.sql` (append near the `rag_trace` alters at line 231)
- Modify: `src/main/java/com/example/springbootrag/trace/RagTrace.java`
- Modify: `src/main/java/com/example/springbootrag/trace/TraceRepository.java`
- Modify: `src/main/java/com/example/springbootrag/trace/TraceRecorder.java`

**Interfaces:**
- Consumes: `MetadataFilter`, `Route` (Task 2).
- Produces:
  - `static String AggregateAnswerer.answer(long count, MetadataFilter filter)`.
  - `static String AggregateAnswerer.CHITCHAT_REPLY` - the canned chit-chat text.
  - `RagTrace` gains a trailing `String route` component (the 13-arg and 15-arg convenience constructors keep working).
  - `TraceRecorder.record(..., String appliedFilter, boolean filterWidened, String route)`.

- [ ] **Step 1: Write the failing answerer test**

Create `src/test/java/com/example/springbootrag/service/AggregateAnswererTest.java`:

```java
package com.example.springbootrag.service;

import com.example.springbootrag.repository.MetadataFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateAnswererTest {

    private static final MetadataFilter ACME = MetadataFilter.parse("""
            {"docType":"invoice","filters":[{"path":"values.customer","op":"eq","value":"ACME Corp"}]}""");

    @Test
    void statesTheCountAndTheFilterThatProducedIt() {
        String answer = AggregateAnswerer.answer(7, ACME);

        assertThat(answer).contains("7").contains("invoice")
                .contains("values.customer = ACME Corp");
    }

    @Test
    void oneRecordReadsAsOneRecord() {
        assertThat(AggregateAnswerer.answer(1, ACME)).contains("1 invoice record matches");
    }

    @Test
    void zeroIsAnAnswerNotAFailure() {
        // Aggregate never widens: a true zero must survive, so the filter is printed beside it and
        // the reader can see a typo'd customer name for what it is.
        String answer = AggregateAnswerer.answer(0, ACME);

        assertThat(answer).contains("0 invoice records match");
        assertThat(answer).contains("values.customer = ACME Corp");
    }

    @Test
    void anEmptyFilterCountsTheWholeScope() {
        String answer = AggregateAnswerer.answer(210, MetadataFilter.none());

        assertThat(answer).isEqualTo("210 records match.");
    }

    @Test
    void aRangeConditionIsRenderedReadably() {
        MetadataFilter over = MetadataFilter.parse("""
                {"docType":"invoice","filters":[{"path":"values.total","op":"range","gt":5000}]}""");

        assertThat(AggregateAnswerer.answer(3, over)).contains("values.total > 5000");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw test "-Dtest=AggregateAnswererTest"`
Expected: FAIL - `AggregateAnswerer` does not exist.

- [ ] **Step 3: Write `AggregateAnswerer`**

Create `src/main/java/com/example/springbootrag/service/AggregateAnswerer.java`:

```java
package com.example.springbootrag.service;

import com.example.springbootrag.repository.MetadataFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a count into a sentence, in code.
 *
 * <p>No model is involved on purpose. A model asked to count from retrieved context is guessing
 * from a sample, and a model handed the right number is an unnecessary opportunity to change it.
 * The filter is printed beside the count because a wrong filter and a true zero look identical
 * otherwise, and this route deliberately does not widen.
 */
public final class AggregateAnswerer {

    /** Fixed reply for the chit-chat route: no claims about the corpus, nothing to cite. */
    public static final String CHITCHAT_REPLY =
            "I answer questions about the documents in this workspace. Ask what a document says, "
                    + "or how many records match something - for example \"how many overdue "
                    + "invoices does ACME have\".";

    private AggregateAnswerer() {}

    public static String answer(long count, MetadataFilter filter) {
        String noun = filter != null && filter.docType() != null && !filter.docType().isBlank()
                ? filter.docType() + " record" : "record";
        String plural = count == 1 ? "" : "s";
        String verb = count == 1 ? "matches" : "match";
        String where = describe(filter);
        return String.format(Locale.ROOT, "%d %s%s %s%s.",
                count, noun, plural, verb, where.isEmpty() ? "" : " " + where);
    }

    /** " values.customer = ACME Corp and values.total > 5000", or empty when nothing was filtered. */
    private static String describe(MetadataFilter filter) {
        if (filter == null || filter.conditions().isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (MetadataFilter.Condition c : filter.conditions()) {
            switch (c.op()) {
                case "eq" -> parts.add(c.path() + " = " + c.value());
                case "in" -> parts.add(c.path() + " in " + c.values());
                case "exists" -> parts.add(c.path() + " is present");
                case "range" -> {
                    if (c.gte() != null) parts.add(c.path() + " >= " + c.gte());
                    if (c.gt() != null) parts.add(c.path() + " > " + c.gt());
                    if (c.lte() != null) parts.add(c.path() + " <= " + c.lte());
                    if (c.lt() != null) parts.add(c.path() + " < " + c.lt());
                }
                default -> parts.add(c.path() + " " + c.op());
            }
        }
        return "where " + String.join(" and ", parts);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./mvnw test "-Dtest=AggregateAnswererTest"`
Expected: PASS, 5 tests. If the "where" wording makes an assertion fail, fix the TEST expectations only if the produced sentence is genuinely clearer - the assertions on substrings (`values.customer = ACME Corp`) must keep passing.

- [ ] **Step 5: Add the trace column**

In `src/main/resources/schema.sql`, next to the existing `rag_trace` alters (around line 231):

```sql
-- ---- Routing (2026-08-08) ----
-- Which path answered this question. A column rather than a stage-map key because rows are
-- filtered by it ("show me every question that took the aggregate path"); route LATENCY stays in
-- stage_latency_ms, which is a JSONB map precisely so a new stage needs no migration.
ALTER TABLE rag_trace ADD COLUMN IF NOT EXISTS route VARCHAR(16);
```

- [ ] **Step 6: Carry route through the trace types**

In `RagTrace.java`, add the component and the javadoc line, and keep both convenience constructors:

```java
 * @param route          which path answered: chitchat, aggregate, or search
 */
public record RagTrace(
        UUID requestId,
        Instant ts,
        String principal,
        List<Long> projectIds,
        String rawQuery,
        String condensedQuery,
        String backend,
        List<Retrieved> retrieved,
        Map<String, Long> stageLatencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String answer,
        String guardReason,
        String appliedFilter,
        boolean filterWidened,
        String route
) {

    /** A trace from a path that does no filtering: no filter, never widened, plain search. */
    public RagTrace(UUID requestId, Instant ts, String principal, List<Long> projectIds,
                    String rawQuery, String condensedQuery, String backend,
                    List<Retrieved> retrieved, Map<String, Long> stageLatencyMs,
                    Integer promptTokens, Integer completionTokens, String answer,
                    String guardReason) {
        this(requestId, ts, principal, projectIds, rawQuery, condensedQuery, backend, retrieved,
                stageLatencyMs, promptTokens, completionTokens, answer, guardReason, null, false,
                null);
    }

    /** Pre-routing callers: filter recorded, route unknown. */
    public RagTrace(UUID requestId, Instant ts, String principal, List<Long> projectIds,
                    String rawQuery, String condensedQuery, String backend,
                    List<Retrieved> retrieved, Map<String, Long> stageLatencyMs,
                    Integer promptTokens, Integer completionTokens, String answer,
                    String guardReason, String appliedFilter, boolean filterWidened) {
        this(requestId, ts, principal, projectIds, rawQuery, condensedQuery, backend, retrieved,
                stageLatencyMs, promptTokens, completionTokens, answer, guardReason, appliedFilter,
                filterWidened, null);
    }
```

In `TraceRepository.insert`, add `route` to the column list, one `?` to the VALUES list, and `t.route()` as the last argument. In `TraceRepository.recent` add `route` to the SELECT list, and in `mapRow()` read `rs.getString("route")` as the final constructor argument.

In `TraceRecorder`, add a route-aware overload and delegate the existing one:

```java
    /** Same, plus which route answered. */
    public UUID record(UUID requestId, SearchContext ctx, List<Long> projectIds, String rawQuery,
                       String condensedQuery, String backend, List<SearchHit> hits,
                       Map<String, Long> stageLatencyMs, Integer promptTokens,
                       Integer completionTokens, String answer, String guardReason,
                       String appliedFilter, boolean filterWidened, String route) {
```

with the 14-argument version delegating with `null` for route, and the body building `RagTrace` with the extra field.

- [ ] **Step 7: Run the trace and full offline suites**

Run: `./mvnw test`
Expected: PASS - 381 tests, 0 failures, 3 skipped. If `TraceRepositoryIntegrationTest`-style tests exist they must still pass unchanged, which is the point of keeping the old constructors.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/springbootrag/service/AggregateAnswerer.java \
        src/test/java/com/example/springbootrag/service/AggregateAnswererTest.java \
        src/main/resources/schema.sql \
        src/main/java/com/example/springbootrag/trace/RagTrace.java \
        src/main/java/com/example/springbootrag/trace/TraceRepository.java \
        src/main/java/com/example/springbootrag/trace/TraceRecorder.java
git commit -m "feat: answer a count in code, and record which route answered"
```

---

## Task 5: Route the `/ask` path

**Files:**
- Modify: `src/main/java/com/example/springbootrag/service/AskService.java`
- Modify: `src/main/java/com/example/springbootrag/web/dto/AskResponse.java`
- Create: `src/test/java/com/example/springbootrag/integration/RoutedAnswerIntegrationTest.java`
- Modify: `src/test/java/com/example/springbootrag/service/AskServiceTest.java` (constructor arity)

**Interfaces:**
- Consumes: `QueryRouter.Decision`, `Route`, `RecordCountRepository.count`, `AggregateAnswerer.answer`, `TraceRecorder.record(..., route)`.
- Produces: `AskResponse(String answer, List<Source> sources, Object appliedFilter, boolean widened, String route)`, with the 4-arg and 2-arg constructors delegating (`route = "search"` for the 4-arg one, which is what those callers did).

- [ ] **Step 1: Write the failing integration test**

Create `src/test/java/com/example/springbootrag/integration/RoutedAnswerIntegrationTest.java`. It uses a stub `ChatProvider` so no model is needed: the stub returns a route label for the routing prompt, an empty filter for the extraction prompt, and a cited sentence for the answer prompt.

```java
package com.example.springbootrag.integration;

import com.example.springbootrag.chat.ChatProvider;
import com.example.springbootrag.embedding.EmbeddingProvider;
import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.security.TestContexts;
import com.example.springbootrag.service.AskService;
import com.example.springbootrag.service.RecordIngestService;
import com.example.springbootrag.web.dto.AskResponse;
import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The three routes, end to end, with a scripted model so the assertions are about routing. */
@SpringBootTest(properties = {"app.graph.edges=structural", "app.understand.facet-ttl-seconds=0"})
@Testcontainers
class RoutedAnswerIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ragdb").withUsername("rag").withPassword("rag");

    @Container
    static QdrantContainer qdrant =
            new QdrantContainer(DockerImageName.parse("qdrant/qdrant:v1.9.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.qdrant.host", qdrant::getHost);
        registry.add("app.qdrant.port", qdrant::getGrpcPort);
    }

    /** Answers by looking at which prompt it was given. */
    @TestConfiguration
    static class ScriptedModelConfig {
        @Bean @Primary
        ChatProvider scriptedChat() {
            return new ChatProvider() {
                @Override public String chat(String systemPrompt, String userPrompt) {
                    return chat(systemPrompt, userPrompt, new Options(null, null, null));
                }

                @Override public String chat(String systemPrompt, String userPrompt, Options options) {
                    if (systemPrompt.contains("Classify the user's message")) {
                        return userPrompt.toLowerCase().startsWith("how many") ? "aggregate"
                                : userPrompt.equalsIgnoreCase("hello") ? "chitchat" : "search";
                    }
                    if (systemPrompt.contains("search filter")) {
                        return """
                               {"docType":"invoice","filters":[
                                 {"path":"values.customer","op":"eq","value":"ACME Corp"}]}""";
                    }
                    return "ACME invoices are billed monthly [1]";
                }
            };
        }

        @Bean @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public float[] embed(String text) {
                    float[] v = new float[768];
                    v[0] = 1f;
                    return v;
                }

                @Override public int dimension() { return 768; }
            };
        }
    }

    @Autowired AskService askService;
    @Autowired RecordIngestService recordIngest;
    @Autowired ProjectRepository projectRepository;

    private static Long projectId;

    @BeforeEach
    void seedOnce() throws Exception {
        if (projectId != null) return;
        long id = projectRepository.create("routing-test", null);
        for (int i = 1; i <= 3; i++) {
            String json = """
                    {"invoiceNumber":"INV-%d","status":"open","total":1000.5,
                     "customer":{"value":"ACME Corp","confidence":0.9},
                     "notes":"ACME invoices are billed monthly and payment is due in 30 days."}
                    """.formatted(i);
            recordIngest.ingest(id, new RecordRequest("INV-" + i, "invoice", M.readTree(json),
                    null, null, null));
        }
        projectId = id;
    }

    @Test
    void chitchatIsAnsweredWithoutRetrieval() {
        AskResponse r = askService.ask(TestContexts.PUBLIC, "hello", List.of(projectId));

        assertThat(r.route()).isEqualTo("chitchat");
        assertThat(r.sources()).isEmpty();
        assertThat(r.answer()).contains("documents in this workspace");
    }

    @Test
    void aCountingQuestionIsAnsweredWithACount() {
        AskResponse r = askService.ask(TestContexts.PUBLIC,
                "how many invoices for ACME Corp", List.of(projectId));

        assertThat(r.route()).isEqualTo("aggregate");
        assertThat(r.answer()).startsWith("3 invoice records match");
        assertThat(r.sources()).isEmpty();
    }

    @Test
    void anOrdinaryQuestionStillTakesTheSearchPath() {
        AskResponse r = askService.ask(TestContexts.PUBLIC,
                "when are ACME invoices billed", List.of(projectId));

        assertThat(r.route()).isEqualTo("search");
        assertThat(r.sources()).isNotEmpty();
        assertThat(r.answer()).contains("[1]");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw test "-Dtest=RoutedAnswerIntegrationTest"`
Expected: FAIL - `AskResponse.route()` does not exist.

- [ ] **Step 3: Widen `AskResponse`**

```java
public record AskResponse(String answer, List<Source> sources,
                          Object appliedFilter, boolean widened, String route) {

    /** Pre-routing callers: the search route, which is what they always took. */
    public AskResponse(String answer, List<Source> sources, Object appliedFilter, boolean widened) {
        this(answer, sources, appliedFilter, widened, "search");
    }

    /** Pre-filter callers: no filter, not widened. */
    public AskResponse(String answer, List<Source> sources) {
        this(answer, sources, null, false, "search");
    }

    public record Source(int index, String docId, String headingPath, double score, String content, int chunkIndex) {}
}
```

- [ ] **Step 4: Branch in `AskService`**

Add `QueryRouter router` and `RecordCountRepository counts` to the constructor and fields, then insert the routing block at the top of `ask(ctx, question, projectIds, callerFilter)`, immediately after the `requestId`/`start` lines:

```java
        // Route first: the cheapest path that can answer correctly wins. An explicit caller filter
        // is a structured request and always takes the search path - the caller already said what
        // they wanted narrowed.
        boolean callerSuppliedFilter = callerFilter != null && !callerFilter.isEmpty();
        QueryRouter.Decision decision = callerSuppliedFilter
                ? new QueryRouter.Decision(Route.SEARCH, 0L, "rule")
                : router.route(question);
        Map<String, Long> routeStage = new LinkedHashMap<>();
        routeStage.put("route", decision.latencyMs());

        if (decision.route() == Route.CHITCHAT) {
            routeStage.put("total", msSince(start));
            tracer.record(requestId, ctx, projectIds, question, null, "none", List.of(), routeStage,
                    null, null, AggregateAnswerer.CHITCHAT_REPLY, null, null, false,
                    Route.CHITCHAT.name().toLowerCase(java.util.Locale.ROOT));
            return new AskResponse(AggregateAnswerer.CHITCHAT_REPLY, List.of(), null, false,
                    "chitchat");
        }
```

Then move the existing extraction lines below that block (they already compute `extraction` and `filter`), and after them add the aggregate branch:

```java
        if (decision.route() == Route.AGGREGATE) {
            // No widening here: zero is a correct count, and retrying without the filter would
            // replace a true zero with a number the user did not ask for.
            long n = counts.count(ctx, projectIds, filter);
            String answer = AggregateAnswerer.answer(n, filter);
            routeStage.put("understand", extraction.latencyMs());
            routeStage.put("count", msSince(start) - decision.latencyMs() - extraction.latencyMs());
            routeStage.put("total", msSince(start));
            tracer.record(requestId, ctx, projectIds, question, null, "count", List.of(), routeStage,
                    null, null, answer, null, FilterJson.toApiString(filter), false, "aggregate");
            return new AskResponse(answer, List.of(), FilterJson.toApiShape(filter), false,
                    "aggregate");
        }
```

In the remaining search path, seed the stage map with the route latency and pass the route to both `tracer.record` calls:

```java
        Map<String, Long> stages = new LinkedHashMap<>(search.stageLatencyMs());
        stages.putAll(routeStage);
```

(keep the existing `stages.put("understand", ...)`), and append `, "search"` as the final argument of each `tracer.record(...)` call in this method.

If the aggregate count query throws, the answer must not fail. Wrap the aggregate branch body:

```java
            try {
                ... as above, returning the aggregate AskResponse ...
            } catch (RuntimeException e) {
                log.warn("count failed; falling back to the search path", e);
            }
```

so control falls through into the normal retrieval below.

- [ ] **Step 5: Fix the existing unit test's constructor call**

`AskServiceTest` builds `AskService` directly. Add the two new constructor arguments there: a `QueryRouter` built with a stub `ChatProvider` returning `"search"` and a `RouteProperties` with `enabled=false` (so those tests keep testing the search path), and `mock(RecordCountRepository.class)`.

- [ ] **Step 6: Run both tests**

Run: `./mvnw test "-Dtest=RoutedAnswerIntegrationTest,AskServiceTest"`
Expected: PASS - 3 new integration tests plus the existing `AskServiceTest` cases.

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: PASS - 384 tests, 0 failures, 3 skipped.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/springbootrag/service/AskService.java \
        src/main/java/com/example/springbootrag/web/dto/AskResponse.java \
        src/test/java/com/example/springbootrag/integration/RoutedAnswerIntegrationTest.java \
        src/test/java/com/example/springbootrag/service/AskServiceTest.java
git commit -m "feat: send each question down the cheapest path that answers it"
```

---

## Task 6: Route the streaming path and emit the frame

**Files:**
- Modify: `src/main/java/com/example/springbootrag/service/ChatService.java`
- Modify: `src/main/java/com/example/springbootrag/web/ChatController.java`
- Modify: `src/test/java/com/example/springbootrag/service/ChatServiceTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2-5.
- Produces:
  - `ChatService.StreamOutcome(List<Source> sources, AnswerGuard.Verdict verdict, UUID requestId, Object appliedFilter, boolean widened, String route)`, with the existing constructors delegating (`route = "search"`).
  - New widest overload `chatStream(ctx, history, projectIds, docIds, think, filter, Consumer<String> onRoute, Consumer<Map<String,Object>> onFilter, Consumer<String> onToken, Consumer<String> onReasoning)`; the previous widest delegates with `r -> {}`.
  - NDJSON frame `{"type":"route","route":"aggregate"}`, emitted before `filter` and before any token.

- [ ] **Step 1: Write the failing tests**

Add to `ChatServiceTest` (it already has a stub `ChatProvider`; extend that stub to answer the routing prompt):

```java
    @Test
    void theRouteFrameArrivesBeforeAnyToken() {
        List<String> order = new ArrayList<>();
        // ... build the service with a router that returns SEARCH ...
        service.chatStream(TestContexts.PUBLIC, List.of(new ChatMessage("user", "what is up")),
                List.of(), List.of(), false, MetadataFilter.none(),
                route -> order.add("route:" + route),
                filter -> order.add("filter"),
                token -> order.add("token"),
                reasoning -> {});

        assertThat(order).isNotEmpty();
        assertThat(order.get(0)).startsWith("route:");
        assertThat(order).doesNotHaveDuplicates();
    }

    @Test
    void chitchatStreamsTheCannedReplyAndRetrievesNothing() {
        // router stub returns CHITCHAT
        StringBuilder out = new StringBuilder();
        ChatService.StreamOutcome outcome = service.chatStream(TestContexts.PUBLIC,
                List.of(new ChatMessage("user", "hi")), List.of(), List.of(), false,
                MetadataFilter.none(), route -> {}, filter -> {}, out::append, r -> {});

        assertThat(outcome.route()).isEqualTo("chitchat");
        assertThat(outcome.sources()).isEmpty();
        assertThat(out.toString()).isEqualTo(AggregateAnswerer.CHITCHAT_REPLY);
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw test "-Dtest=ChatServiceTest"`
Expected: FAIL - no 10-argument `chatStream` overload, no `route()` on `StreamOutcome`.

- [ ] **Step 3: Widen `StreamOutcome` and add the overload**

```java
    public record StreamOutcome(List<AskResponse.Source> sources, AnswerGuard.Verdict verdict,
                                java.util.UUID requestId, Object appliedFilter, boolean widened,
                                String route) {

        /** A turn that did no filtering. */
        public StreamOutcome(List<AskResponse.Source> sources, AnswerGuard.Verdict verdict,
                             java.util.UUID requestId) {
            this(sources, verdict, requestId, null, false, "search");
        }

        /** Pre-routing callers: the search route. */
        public StreamOutcome(List<AskResponse.Source> sources, AnswerGuard.Verdict verdict,
                             java.util.UUID requestId, Object appliedFilter, boolean widened) {
            this(sources, verdict, requestId, appliedFilter, widened, "search");
        }
    }
```

The previous widest overload delegates:

```java
    public StreamOutcome chatStream(SearchContext ctx, List<ChatMessage> history,
                                    List<Long> projectIds, List<String> docIds, boolean think,
                                    MetadataFilter filter,
                                    Consumer<Map<String, Object>> onFilter,
                                    Consumer<String> onToken, Consumer<String> onReasoning) {
        return chatStream(ctx, history, projectIds, docIds, think, filter, r -> {}, onFilter,
                onToken, onReasoning);
    }
```

- [ ] **Step 4: Branch inside the new widest overload**

After the history validation and before condensing, add:

```java
        boolean callerSuppliedFilter = filter != null && !filter.isEmpty();
        QueryRouter.Decision decision = callerSuppliedFilter
                ? new QueryRouter.Decision(Route.SEARCH, 0L, "rule")
                : router.route(last.content());
        String routeName = decision.route().name().toLowerCase(java.util.Locale.ROOT);
        // Before anything else the client can render: the route explains why an answer has no
        // citations long before the answer itself arrives.
        onRoute.accept(routeName);

        if (decision.route() == Route.CHITCHAT) {
            onToken.accept(AggregateAnswerer.CHITCHAT_REPLY);
            Map<String, Long> chit = new LinkedHashMap<>();
            chit.put("route", decision.latencyMs());
            chit.put("total", msSince(start));
            tracer.record(requestId, ctx, pScope, last.content(), null, "none", List.of(), chit,
                    null, null, AggregateAnswerer.CHITCHAT_REPLY, null, null, false, routeName);
            return new StreamOutcome(List.of(),
                    new AnswerGuard.Verdict(true, "chitchat", AggregateAnswerer.CHITCHAT_REPLY),
                    requestId, null, false, routeName);
        }
```

Note the ordering constraint: `pScope`/`dScope` are computed below in the current code, so move those two lines ABOVE this block. The aggregate branch goes after extraction, mirroring `AskService` (same count call, same no-widen rule, same fall-through on failure), streaming the sentence through `onToken` in one piece and returning a `StreamOutcome` with `route = "aggregate"`. Every remaining `tracer.record` in the method gains `routeName` as its final argument, and the stage map gains `stages.put("route", decision.latencyMs())`.

- [ ] **Step 5: Emit the frame in the controller**

In `ChatController.stream`, pass a route consumer as the new argument and document the frame in the class javadoc:

```java
 *   {"type":"route","route":"search"}  - which path is answering, first of all
```

```java
                                route -> writeFrame(out, Map.of("type", "route", "route", route)),
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw test "-Dtest=ChatServiceTest"`
Expected: PASS, including the two new cases.

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: PASS - 386 tests, 0 failures, 3 skipped.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/springbootrag/service/ChatService.java \
        src/main/java/com/example/springbootrag/web/ChatController.java \
        src/test/java/com/example/springbootrag/service/ChatServiceTest.java
git commit -m "feat: stream the route before the answer it explains"
```

---

## Task 7: Show the route and the filter in the UI

The `filter` frame has been emitted since 2026-08-07 and dropped on the floor by `app.js`, which handles only `reasoning`, `token`, `sources`, `trace`, `guard` and `error`. This task renders both.

**Files:**
- Modify: `src/main/resources/static/app.js:1053-1077` (frame loop), `:875-905` (`renderThread`)
- Modify: `src/main/resources/static/style.css`

**Interfaces:**
- Consumes: the `route` and `filter` NDJSON frames.
- Produces: nothing other code depends on.

- [ ] **Step 1: Store both frames**

In the streaming frame loop, add two branches before the `error` branch:

```javascript
                } else if (frame.type === 'route') {
                    assistant.route = frame.route;
                } else if (frame.type === 'filter') {
                    // Emitted before the first token: a narrowed search has to be visible while
                    // the answer is being read, not discovered afterwards in the trace panel.
                    assistant.filter = frame.applied || null;
                    assistant.widened = !!frame.widened;
```

- [ ] **Step 2: Render the chips**

In `renderThread`, directly after the `bubble` element is created and before the reasoning block, add:

```javascript
        // Route + applied filter, above the answer: why this answer has no citations, or why it
        // found less than expected, is a property of the request, not a footnote.
        if (m.role === 'assistant' && (m.route || m.filter || m.widened)) {
            const meta = document.createElement('div');
            meta.className = 'meta-chips';
            if (m.route && m.route !== 'search') {
                const chip = document.createElement('span');
                chip.className = 'meta-chip route-' + m.route;
                chip.textContent = m.route === 'aggregate' ? 'counted' : 'no search needed';
                chip.title = 'Route: ' + m.route;
                meta.appendChild(chip);
            }
            if (m.filter) {
                const chip = document.createElement('span');
                chip.className = 'meta-chip';
                chip.textContent = 'filtered: ' + describeFilter(m.filter);
                chip.title = JSON.stringify(m.filter);
                meta.appendChild(chip);
            }
            if (m.widened) {
                const chip = document.createElement('span');
                chip.className = 'meta-chip meta-chip-warn';
                chip.textContent = 'filter matched nothing - searched everything';
                meta.appendChild(chip);
            }
            bubble.appendChild(meta);
        }
```

And add the helper next to `toggleSource`:

```javascript
// "invoice · values.customer = ACME Corp" - the applied filter in one short line.
function describeFilter(applied) {
    const parts = [];
    if (applied.docType) parts.push(applied.docType);
    for (const f of applied.filters || []) {
        if (f.op === 'eq') parts.push(`${f.path} = ${f.value}`);
        else if (f.op === 'in') parts.push(`${f.path} in [${(f.values || []).join(', ')}]`);
        else if (f.op === 'exists') parts.push(`${f.path} present`);
        else parts.push(`${f.path} ${['gte', 'gt', 'lte', 'lt'].filter((k) => f[k] !== undefined && f[k] !== null).map((k) => `${k} ${f[k]}`).join(' ')}`);
    }
    return parts.join(' · ');
}
```

- [ ] **Step 3: Style the chips**

Append to `style.css`:

```css
/* Request-level chips (route, applied filter) above an assistant answer. */
.meta-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.meta-chip {
    font-size: 11px; line-height: 1.6; padding: 1px 8px; border-radius: 999px;
    background: var(--bg); border: 1px solid var(--border); color: var(--muted);
}
.meta-chip-warn { border-color: #f59e0b; color: #b45309; }
```

Check the variable names against the file first - if `--muted` or `--bg` do not exist, use the names the surrounding rules use.

- [ ] **Step 4: Verify in a browser**

Run: `./mvnw spring-boot:run` (server on :8085; log in with the basic-auth user from `application.yml`).
Ask "hi" -> a "no search needed" chip and the canned reply, no citations.
Ask "how many invoices are there" against a project holding records -> a "counted" chip and a number.
Ask a normal document question -> no route chip (search is the unremarkable default), citations as before.
Expected: all three render, and nothing shifts layout on an ordinary answer.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/app.js src/main/resources/static/style.css
git commit -m "feat: show the route and the applied filter above the answer"
```

---

## Task 8: Score routing in the records eval

All offline. No live model needed for this task - the gate logic is unit-tested in milliseconds, which is the only reason it can be trusted inside a 30-minute eval.

**Files:**
- Modify: `src/test/resources/eval/records-golden.yaml`
- Modify: `src/test/java/com/example/springbootrag/eval/RecordGoldenEntry.java`
- Modify: `src/test/java/com/example/springbootrag/eval/RecordGoldenSet.java`
- Modify: `src/test/java/com/example/springbootrag/eval/RecordEvalBaseline.java`
- Modify: `src/test/java/com/example/springbootrag/eval/RecordEvalBaselineStore.java`
- Modify: `src/test/java/com/example/springbootrag/eval/RecordEvalComparison.java`
- Modify: `src/test/java/com/example/springbootrag/eval/RecordEvalComparisonTest.java`
- Modify: `src/test/java/com/example/springbootrag/eval/RecordFilterEvalTest.java`

**Interfaces:**
- Consumes: `QueryRouter`, `RecordCountRepository`, `RecordGroundTruth.matchingDocIds`.
- Produces:
  - `RecordGoldenEntry(..., String expectedRoute)` - defaults to `"search"` when the YAML omits it.
  - `RecordEvalBaseline.Routing(double routeAccuracy, int aggregateCountCorrect)` and `List<String> routes` (parallel to `questions`).
  - Baseline YAML block `routing: {routeAccuracy, aggregateCountCorrect}` plus a top-level `routes:` list.

- [ ] **Step 1: Extend the golden file**

Add `expectedRoute: search` to each of the 15 existing entries (mechanical), then append the 6 new ones:

```yaml
# ---- Routing (2026-08-08) ----
# Two questions that must never reach retrieval, and four that must be answered by counting.
# Counts are NOT written here: RecordGroundTruth derives them from RecordCorpus.generate(42), so a
# regenerated corpus updates them automatically instead of silently making this file lie.

- question: "hi"
  expectedRoute: chitchat
  expectNoFilter: true

- question: "what can you do"
  expectedRoute: chitchat
  expectNoFilter: true

- question: "how many invoices for ACME Corp"
  expectedRoute: aggregate
  expectedDocType: invoice
  expectedFilters:
    - {path: values.customer, op: eq, value: "ACME Corp"}

- question: "how many overdue invoices are there"
  expectedRoute: aggregate
  expectedDocType: invoice
  expectedFilters:
    - {path: values.status, op: eq, value: overdue}

- question: "how many delivery notes are there"
  expectedRoute: aggregate
  expectedDocType: delivery-note
  expectedFilters: []

- question: "how many contracts with Initech"
  expectedRoute: aggregate
  expectedDocType: contract
  expectedFilters:
    - {path: values.party, op: eq, value: Initech}
```

Note the deliberate overlap: the two chit-chat entries also carry `expectNoFilter: true`, because a route that skips extraction trivially satisfies it and a router regression that sends "hi" to search would then show up twice.

- [ ] **Step 2: Parse the new field**

`RecordGoldenEntry` gains a trailing `String expectedRoute`; `RecordGoldenSet.load` reads it with a default:

```java
                        (String) m.getOrDefault("expectedRoute", "search")));
```

- [ ] **Step 3: Write the failing comparison tests**

Add to `RecordEvalComparisonTest`:

```java
    @Test
    void aDropInRouteAccuracyFailsWithNoTolerance() {
        RecordEvalBaseline expected = baseline(1.0, 4);
        RecordEvalBaseline actual = baseline(0.95, 4);

        assertThat(RecordEvalComparison.compare(expected, actual, 0.05))
                .extracting(RecordEvalComparison.Violation::area).contains("routing");
    }

    @Test
    void aWrongCountFailsEvenWhenRouteAccuracyHolds() {
        RecordEvalBaseline expected = baseline(1.0, 4);
        RecordEvalBaseline actual = baseline(1.0, 3);

        assertThat(RecordEvalComparison.compare(expected, actual, 0.05))
                .anyMatch(v -> v.detail().contains("aggregate count"));
    }

    @Test
    void aQuestionThatChangedRouteIsNamed() {
        // The aggregate half of the aggregates is the failure that matters: an aggregate question
        // silently demoted to search still answers, just with ten chunks and no number.
        RecordEvalBaseline expected = withRoutes(List.of("how many invoices"), List.of("aggregate"));
        RecordEvalBaseline actual = withRoutes(List.of("how many invoices"), List.of("search"));

        assertThat(RecordEvalComparison.compare(expected, actual, 0.05))
                .anyMatch(v -> v.detail().contains("how many invoices")
                        && v.detail().contains("aggregate")
                        && v.detail().contains("search"));
    }

    @Test
    void anImprovedRouteAccuracyNeverFails() {
        assertThat(RecordEvalComparison.compare(baseline(0.9, 3), baseline(1.0, 4), 0.05)).isEmpty();
    }
```

(`baseline(...)` and `withRoutes(...)` are small local helpers in that test class, built on whatever fixture builder it already uses.)

- [ ] **Step 4: Run them and watch them fail**

Run: `./mvnw test "-Dtest=RecordEvalComparisonTest"`
Expected: FAIL - `RecordEvalBaseline` has no routing block.

- [ ] **Step 5: Extend the baseline model, store and comparison**

`RecordEvalBaseline` gains two components and a nested record:

```java
        List<String> routes,
        Routing routing) {

    /**
     * How well questions were routed.
     *
     * @param routeAccuracy         share of golden questions routed as expected
     * @param aggregateCountCorrect of the aggregate questions, how many produced the count ground
     *                              truth says is right. A count, not a ratio: there are four and a
     *                              ratio hides which one broke
     */
    public record Routing(double routeAccuracy, int aggregateCountCorrect) {}
```

`RecordEvalBaselineStore.toMap` writes `routes` and a `routing` block; `parse` reads them, defaulting to an empty list and `new Routing(0, 0)` when absent so an older baseline file still loads and reports rather than exploding.

`RecordEvalComparison.compare` gains, after the extraction block:

```java
        // Routing is gated with NO tolerance. A misroute is not a slightly worse answer, it is the
        // wrong shape of answer: a counting question answered with prose, or a document question
        // answered with a number.
        if (actual.routing().routeAccuracy() < expected.routing().routeAccuracy()) {
            violations.add(new Violation("routing", String.format(Locale.ROOT,
                    "route accuracy %.3f is below the baseline %.3f",
                    actual.routing().routeAccuracy(), expected.routing().routeAccuracy())));
        }
        if (actual.routing().aggregateCountCorrect() < expected.routing().aggregateCountCorrect()) {
            violations.add(new Violation("routing", String.format(Locale.ROOT,
                    "aggregate count correct %d, baseline had %d",
                    actual.routing().aggregateCountCorrect(),
                    expected.routing().aggregateCountCorrect())));
        }
        for (int i = 0; i < expected.questions().size() && i < expected.routes().size(); i++) {
            String question = expected.questions().get(i);
            int at = actual.questions().indexOf(question);
            if (at < 0 || at >= actual.routes().size()) continue;
            String was = expected.routes().get(i);
            String now = actual.routes().get(at);
            if (!was.equals(now)) {
                violations.add(new Violation("routing", String.format(Locale.ROOT,
                        "route changed for \"%s\": %s -> %s", question, was, now)));
            }
        }
```

- [ ] **Step 6: Run the comparison tests**

Run: `./mvnw test "-Dtest=RecordEvalComparisonTest"`
Expected: PASS, including the four new cases.

- [ ] **Step 7: Score routing inside the eval**

In `RecordFilterEvalTest`: autowire `QueryRouter router` and `RecordCountRepository counts`; per question, call `router.route(entry.question())` FIRST, record `decision.route()`, and

- skip extraction and retrieval entirely when the route is `CHITCHAT` (it is what the feature does, and scoring the extraction of a question that never reaches extraction would measure a path that no longer runs),
- for `AGGREGATE`, extract as usual, then compare `counts.count(TestContexts.PUBLIC, List.of(projectId), got)` against `RecordGroundTruth.matchingDocIds(corpus, entry).size()` and increment `aggregateCountCorrect` on equality,
- print one line per question: `route=<route> (<source>, <ms> ms)`, then the existing extraction line,
- accumulate `routes` in golden order and `routeAccuracy = correctRoutes / golden.size()`,
- print a routing block in the report, including router p50 next to extraction p50,
- pass `routes` and `new RecordEvalBaseline.Routing(...)` into the measured baseline.

- [ ] **Step 8: Compile the eval without running it**

Run: `./mvnw test-compile`
Expected: BUILD SUCCESS. (`-Dgroups=eval-records` is excluded by default, so a compile check is the fast feedback here.)

- [ ] **Step 9: Run the full offline suite**

Run: `./mvnw test`
Expected: PASS - 390 tests, 0 failures, 3 skipped.

- [ ] **Step 10: Commit**

```bash
git add src/test/resources/eval/records-golden.yaml src/test/java/com/example/springbootrag/eval/
git commit -m "test: score and gate routing in the records eval"
```

---

## Task 9: Measure it, then decide what to keep

This is where the plan stops asserting and starts measuring. Budget 3-5 runs at ~30 minutes. Docker and Ollama must both be up.

**Files:**
- Modify: `src/test/resources/eval/baseline-records.yaml` (regenerated, then reviewed by hand)
- Modify: `docs/LEARNINGS.md`, `docs/RAG-MASTERY.md`, `docs/ARCHITECTURE.md`, `README.md`, `docs/implementation-notes.md`

- [ ] **Step 1: Start the stack**

Run: `docker compose up -d` and confirm Ollama is serving `qwen3:4b` (`ollama list`).
Expected: Postgres on 5432, Qdrant on 6333/6334.

- [ ] **Step 2: Regenerate the baseline**

The `questions:` list is the golden-set fingerprint and six questions were added, so the committed baseline is stale by construction.

Run: `./mvnw test "-Dgroups=eval-records" "-DexcludedGroups=" "-Deval.baseline.update=true"`
Expected: ~30 minutes, per-question lines printed as it goes, and `baseline WRITTEN` at the end.

- [ ] **Step 3: Read the diff before trusting it**

Run: `git diff src/test/resources/eval/baseline-records.yaml`
Check: extraction numbers should be close to `conditionPrecision 0.813 / conditionRecall 0.867 / docTypeAccuracy 1.0` - a large move means routing changed extraction, which it must not. `routeAccuracy` and `aggregateCountCorrect` are new. If extraction moved materially, stop and investigate rather than committing the new numbers.

- [ ] **Step 4: Prove the gate is stable**

Run: `./mvnw test "-Dgroups=eval-records" "-DexcludedGroups="`
Expected: PASS with no violations. A failure here on an unchanged codebase means something in the new path is non-deterministic - the same defect that `temperature 0 + seed` was introduced to fix on 2026-08-07, and it must be fixed rather than absorbed into a wider tolerance.

- [ ] **Step 5: Record the routing numbers**

Write into `docs/LEARNINGS.md` as a new section 21: route accuracy, aggregate count correctness, router p50 latency, extraction p50, and the per-route latency table. State plainly whether the router's own cost is small next to the extraction call it skips. The failure criteria from spec section 8 are the yardstick: route accuracy below 0.90 means the router is guessing.

- [ ] **Step 6: Latency experiment - cap extraction output**

Set `app.understand.num-predict`-equivalent by passing the cap in `QueryUnderstanding.extract` (`new ChatProvider.Options(model(), 0.0, EXTRACTION_SEED, null, 512)`), then run the gate.

Run: `./mvnw test "-Dgroups=eval-records" "-DexcludedGroups="`
Expected: PASS with a lower extraction p50 -> keep the change. Any violation -> revert it and write down that the cap could not be applied without losing filter quality.

- [ ] **Step 7: Latency experiment - extraction without thinking**

Change the same call to `think=false` and run the gate again.

Run: `./mvnw test "-Dgroups=eval-records" "-DexcludedGroups="`
Expected: either a large p50 drop with the gate holding (keep), or dropped conditions from chain-of-thought polluting the JSON (revert, and record the number that made the decision). Do not keep a change whose only evidence is that it "should" be faster.

- [ ] **Step 8: Live smoke on the real app**

Run: `./mvnw spring-boot:run`, then ask on :8085 - "hi", "how many invoices are there" against a records project, and an ordinary document question. Check `GET /traces` shows `route` per row and a `route` entry in `stage_latency_ms`.
Expected: three distinct behaviours, chips rendered, trace populated.

- [ ] **Step 9: Update the documentation**

- `docs/RAG-MASTERY.md`: re-score rows 4 and 8 against evidence, including the case where a lever was reverted. Say what still holds them back.
- `docs/ARCHITECTURE.md`: add the route decision to the request-path section, with the three branches.
- `README.md`: document `app.route.*` in the config table and the `route` frame in the streaming docs.
- `docs/implementation-notes.md`: the three planning deviations listed at the top of this plan, plus every decision taken during execution.

- [ ] **Step 10: Commit**

```bash
git add src/test/resources/eval/baseline-records.yaml docs/ README.md \
        src/main/java/com/example/springbootrag/understand/QueryUnderstanding.java
git commit -m "test: gate routing against a regenerated records baseline"
```

---

## Self-Review

**Spec coverage.** Section 1 routes and rules -> Task 2. Router cost (`think:false`, `numPredict`, tolerant parse) -> Tasks 1 and 2. Section 2 aggregate route -> Tasks 3 and 4. Section 3 latency levers -> Task 1 (mechanism), Task 5 (skip on chit-chat), Task 9 steps 6-7 (the two measured experiments). Section 4 wiring and visibility -> Tasks 4, 5, 6, 7. Section 5 failure modes -> Task 2 (disabled/unknown/exception), Task 5 (aggregate SQL fall-through), Task 3 (access labels). Section 6 eval and gate -> Task 8 and Task 9. Section 7 testing -> the test steps of Tasks 1-8. Section 8 failure criteria -> Task 9 step 5.

**Deviations** are listed at the top rather than buried: the router signature, no `route_latency_ms` column, and one more `ChatService` overload instead of a `Signals` refactor.

**Type consistency check.** `Route` / `QueryRouter.Decision(route, latencyMs, source)` / `QueryRouter.route(String)` / `RecordCountRepository.count(SearchContext, List<Long>, MetadataFilter)` / `AggregateAnswerer.answer(long, MetadataFilter)` / `AggregateAnswerer.CHITCHAT_REPLY` / `AskResponse.route()` / `StreamOutcome.route()` / `RagTrace.route()` / `RecordEvalBaseline.Routing(routeAccuracy, aggregateCountCorrect)` are used with the same names and arities in every task that references them.

**Test-count arithmetic** in the "run the full suite" steps assumes the current 363 and is indicative, not a gate. What matters at each step is 0 failures and 3 skipped.
