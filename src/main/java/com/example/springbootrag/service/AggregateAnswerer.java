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
 * The filter is printed beside the count because this route deliberately does not widen, so a
 * wrong filter and a true zero would otherwise look identical.
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

    /** "where values.customer = ACME Corp and values.total > 5000", empty when nothing was filtered. */
    private static String describe(MetadataFilter filter) {
        if (filter == null || filter.conditions().isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (MetadataFilter.Condition c : filter.conditions()) {
            switch (c.op()) {
                case "eq" -> parts.add(c.path() + " = " + c.value());
                case "in" -> parts.add(c.path() + " in [" + join(c.values()) + "]");
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

    private static String join(List<Object> values) {
        List<String> out = new ArrayList<>();
        for (Object v : values) out.add(String.valueOf(v));
        return String.join(", ", out);
    }
}
