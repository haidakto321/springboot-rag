package com.example.springbootrag.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ingest-time scan for credentials, and the one scanner in this package that BLOCKS.
 *
 * <p>Separate from {@link InjectionScanner} because the two close different gaps. Instruction
 * injection is already defended by {@link PromptFence} plus {@link AnswerGuard}, measured on
 * 2026-08-05, so phrasing stays a warning. Content disclosure - a secret sitting in the corpus,
 * retrieved faithfully by a caller who is allowed to read the document - has no other control, and
 * a prompt rule is not one: the drill's system prompt said never to reveal credentials and the
 * model revealed them anyway.
 *
 * <p>The same honesty {@link InjectionScanner} states applies here: this is a denylist. It will
 * miss a careful attacker and it will fire on a document that merely discusses credentials. It is
 * a smoke alarm with a door lock attached, not proof of safety.
 */
public final class SecretScanner {

    /**
     * One match. {@code excerpt} is masked - a finding is returned over the API and written to a
     * log, and reprinting the value there would move the secret from one place it should not be
     * into two.
     */
    public record Finding(String rule, String label, String excerpt) {}

    private record Rule(Pattern pattern, String name, String label) {}

    /**
     * A credential keyword, a separator, then a value. The value must be present: "rotate the
     * recovery code quarterly" is a sentence about security, and quarantining it would train
     * whoever clicks release to always click it.
     */
    private static final Pattern LABELLED = Pattern.compile(
            "(?<label>password|passphrase|recovery code|access code|api[ _-]?key|secret|token|credentials?)"
                    // Up to ~40 characters may sit between the keyword and the separator:
                    // "the recovery code FOR PROD is hunter2" is the same disclosure as the bare
                    // form, and an adjacency-only rule misses it.
                    // The separator words need their own boundaries: without them "token
                    // island-hopping" parses as "is" + the value "land-hopping".
                    + "[^\\n]{0,40}?(?:\\bis\\b|\\bare\\b|=|:)\\s*"
                    + "(?<value>[A-Za-z0-9._/+\\-]{4,})",
            Pattern.CASE_INSENSITIVE);

    /**
     * Words that follow a credential keyword in ordinary prose and are never the secret itself.
     *
     * <p>This scanner is deliberately high-recall: any value after a credential keyword is held,
     * because no regex can tell "swordfish" from "expired" by shape, and an earlier attempt to do
     * it by shape (a digit-or-separator test) silently let every all-letter password through. The
     * cost of that choice is false positives on sentences like "the password is expired", so the
     * exceptions are enumerated here, explicitly and testably, rather than inferred.
     *
     * <p>Compound words are matched on their last segment, so "role-based", "project-scoped" and
     * "auto-generated" are all covered by the plain forms below.
     */
    private static final Set<String> NOT_SECRETS = Set.of(
            "expired", "required", "missing", "stored", "based", "scoped", "generated", "supplied",
            "valid", "invalid", "correct", "incorrect", "empty", "null", "none", "unknown",
            "set", "unset", "rotated", "shared", "disabled", "enabled", "optional", "mandatory",
            "hashed", "encrypted", "needed", "wrong", "provided", "configured", "available",
            "unavailable", "used", "reused", "visible", "hidden", "safe", "unsafe", "secure",
            "insecure", "sensitive", "case-sensitive", "here", "there", "below", "above");

    private static final List<Rule> SHAPES = List.of(
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9]{20,}"), "provider-key", "OpenAI-style key"),
            new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"), "provider-key", "GitHub token"),
            new Rule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), "provider-key", "AWS access key id"),
            new Rule(Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"), "provider-key", "Slack token"),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\."), "provider-key", "JSON Web Token"),
            new Rule(Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"),
                    "private-key", "private key block"));

    private SecretScanner() {}

    /**
     * Whether a captured value is a secret rather than the next word of an English sentence.
     *
     * <p>Everything that is not an enumerated prose word counts as a secret. That direction is
     * deliberate: a miss leaves a credential in the index permanently, while a false positive
     * costs one click on release. Judging by shape instead was tried and failed - requiring a
     * digit or a separator let "the recovery code is swordfish" through untouched.
     */
    private static boolean isSecretValue(String value) {
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[.,;:!?)\\]]+$", "");
        if (cleaned.isBlank() || isProse(cleaned)) {
            return false;
        }
        // "role-based", "auto-generated": the last segment carries the meaning.
        int dash = cleaned.lastIndexOf('-');
        return dash < 0 || !isProse(cleaned.substring(dash + 1));
    }

    /**
     * Whether a word is English rather than a credential.
     *
     * <p>The participle rule does most of the work and generalises where a word list cannot:
     * "expired", "required", "stored", "generated", "documented" and every other lowercase word
     * ending in -ed or -ing is prose, while "hunter2", "swordfish" and "correcthorse" are not.
     * {@link #NOT_SECRETS} then covers the words that fit no rule.
     *
     * <p>The cost, stated rather than discovered later: a real password that is a lowercase word
     * ending in -ed or -ing is missed. That is the trade for not quarantining every sentence
     * containing the word "token".
     */
    private static boolean isProse(String word) {
        if (NOT_SECRETS.contains(word)) {
            return true;
        }
        return word.chars().allMatch(Character::isLetter)
                && (word.endsWith("ed") || word.endsWith("ing"));
    }

    /** Findings with masked excerpts, empty when nothing matched. */
    public static List<Finding> scan(String text) {
        List<Finding> found = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return found;
        }
        Matcher m = LABELLED.matcher(text);
        while (m.find()) {
            if (isSecretValue(m.group("value"))) {
                found.add(new Finding("labelled-credential",
                        m.group("label").toLowerCase(Locale.ROOT),
                        m.group("label") + " = ***"));
            }
        }
        for (Rule r : SHAPES) {
            if (r.pattern().matcher(text).find()) {
                found.add(new Finding(r.name(), r.label(), r.label() + " = ***"));
            }
        }
        return found;
    }
}
