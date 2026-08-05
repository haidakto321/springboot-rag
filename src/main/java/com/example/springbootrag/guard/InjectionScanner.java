package com.example.springbootrag.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ingest-time smell test for pages that try to talk to the model.
 *
 * <p>Deliberately a denylist of well-known phrasings, and deliberately NOT a blocker: a rule this
 * crude will miss a careful attacker and will fire on a legitimate page that discusses prompt
 * injection (this repo's own docs, for one). It exists because scanning before ingest is cheap and
 * because a warning at upload time is the moment a human is actually looking.
 *
 * <p>The real defence is {@link PromptFence} plus {@link AnswerGuard}; this is the smoke alarm,
 * not the sprinkler.
 */
public final class InjectionScanner {

    private record Rule(Pattern pattern, String label) {}

    private static final List<Rule> RULES = List.of(
            rule("ignore (all |any |the )?(previous|prior|above|earlier) (instructions|prompts?|rules)",
                    "asks the model to ignore previous instructions"),
            rule("disregard (all |any |the )?(previous|prior|above|earlier)",
                    "asks the model to disregard earlier context"),
            rule("(you are|enter) (now )?in (maintenance|developer|debug|god) mode",
                    "claims a special operating mode"),
            rule("system (update|notice|override|prompt)\\b",
                    "impersonates a system message"),
            rule("do not (cite|mention|reveal|tell)",
                    "asks the model to hide something from the user"),
            rule("reply with exactly|respond with exactly|output exactly",
                    "dictates the model's exact output"),
            rule("</?(system|assistant|instructions?)>",
                    "contains role or instruction markup"));

    private static Rule rule(String regex, String label) {
        return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), label);
    }

    private InjectionScanner() {}

    /** Human-readable warnings, empty when nothing matched. Order follows the rule list. */
    public static List<String> scan(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }
        for (Rule r : RULES) {
            if (r.pattern().matcher(text).find()) {
                hits.add(r.label());
            }
        }
        return hits;
    }
}
