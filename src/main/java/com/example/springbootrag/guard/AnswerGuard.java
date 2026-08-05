package com.example.springbootrag.guard;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output check: never ship an answer that claims more than the sources support.
 *
 * <p>Two failure modes, one rule. An answer with no citation is ungrounded - it may have come from
 * the model's memory, or from an instruction someone hid in a document. An answer citing chunk [7]
 * when six were supplied is a fabricated citation, which is worse than no citation because it
 * looks verified.
 *
 * <p>This is the cheap, deterministic half of injection defence: a fenced prompt asks the model to
 * behave, and this refuses to publish the result when it did not. No LLM call, no heuristics about
 * "does this look like an attack" - just "is every claim traceable to material we actually
 * retrieved".
 */
public final class AnswerGuard {

    /** The exact wording the system prompt asks for when the material does not cover the question. */
    public static final String REFUSAL = "Not found in knowledge base.";

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})]");

    private AnswerGuard() {}

    /**
     * @param allowed whether the answer may be shown as-is
     * @param reason  machine-readable cause when it may not: "empty", "ungrounded", "bad-citation"
     * @param answer  the text to show - the original when allowed, the refusal when not
     */
    public record Verdict(boolean allowed, String reason, String answer) {}

    public static Verdict check(String answer, int chunkCount) {
        String text = answer == null ? "" : answer.strip();
        if (text.isEmpty()) {
            return new Verdict(false, "empty", REFUSAL);
        }
        // An explicit refusal is a correct, grounded outcome and carries no citation by design.
        if (text.startsWith(REFUSAL)) {
            return new Verdict(true, "refusal", text);
        }

        Set<Integer> cited = citations(text);
        if (cited.isEmpty()) {
            return new Verdict(false, "ungrounded", REFUSAL);
        }
        for (int n : cited) {
            if (n < 1 || n > chunkCount) {
                return new Verdict(false, "bad-citation", REFUSAL);
            }
        }
        return new Verdict(true, "cited", text);
    }

    static Set<Integer> citations(String text) {
        Set<Integer> found = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(text);
        while (m.find()) {
            found.add(Integer.parseInt(m.group(1)));
        }
        return found;
    }
}
