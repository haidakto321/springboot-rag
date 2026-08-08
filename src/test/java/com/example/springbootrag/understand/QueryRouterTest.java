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
    void aGreetingRuleNeverFiresOnASubstring() {
        // "thanks for the invoice policy" contains "thanks" and is a document question. The rules
        // match the whole message on purpose; anything looser answers real questions with "hello".
        StubChat chat = new StubChat();

        QueryRouter.Decision d = router(chat, enabled()).route("thanks for the invoice policy");

        assertThat(d.route()).isEqualTo(Route.SEARCH);
        assertThat(chat.calls).isEqualTo(1);
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
    void theSchemaReplyIsTheExpectedShape() {
        StubChat chat = new StubChat();
        chat.reply = q -> "{\"route\":\"aggregate\"}";

        assertThat(router(chat, enabled()).route("how many invoices").route())
                .isEqualTo(Route.AGGREGATE);
    }

    @Test
    void theCallIsConstrainedByAResponseSchema() {
        StubChat chat = new StubChat();

        router(chat, enabled()).route("how many invoices");

        assertThat(chat.lastOptions.responseSchema()).isNotNull();
        assertThat(chat.lastOptions.responseSchema().toString())
                .contains("chitchat").contains("aggregate").contains("search");
    }

    @Test
    void aReplyThatMerelyListsTheOptionsIsNotADecision() {
        // Measured on qwen3:4b without a schema: it spent its whole budget restating the task and
        // never chose. Reading the first name would have sent almost every question to chitchat,
        // which answers nothing - so several names means unknown, and unknown means search.
        StubChat chat = new StubChat();
        chat.reply = q -> "We need to classify it into exactly one route: chitchat, aggregate";

        QueryRouter.Decision d = router(chat, enabled()).route("how many invoices");

        assertThat(d.route()).isEqualTo(Route.SEARCH);
        assertThat(d.source()).isEqualTo("fallback");
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

    @Test
    void theQuestionIsTheUserTurnAndTheRulesAreTheSystemTurn() {
        StubChat chat = new StubChat();

        router(chat, enabled()).route("what does the policy say");

        assertThat(chat.lastUser).isEqualTo("what does the policy say");
        assertThat(chat.lastSystem).isEqualTo(QueryRouter.buildPrompt());
    }
}
