package com.example.springbootrag.guard;

import java.util.List;

/**
 * Thrown by the ingest chokepoint when text carrying a credential is about to be indexed.
 *
 * <p>An exception rather than a return value because it has to be unignorable. The first version
 * of this control scanned inside the two ingest CONTROLLERS, and a review found two further paths
 * - {@code POST /ingest} and the wiki importer - that reached the index without ever meeting it.
 * A check that every future caller must remember to make is a check that will eventually be
 * forgotten; a throw from the one method they all funnel through cannot be.
 *
 * <p>Callers that know the document's original form catch this and store it in the pen. Callers
 * that do not, do not catch it, and the ingest fails loudly instead of silently indexing a secret.
 */
public class QuarantineRequiredException extends RuntimeException {

    private final transient List<SecretScanner.Finding> findings;

    public QuarantineRequiredException(String docId, List<SecretScanner.Finding> findings) {
        super("document '" + docId + "' carries credential-shaped text: "
                + findings.stream().map(SecretScanner.Finding::rule).toList());
        this.findings = List.copyOf(findings);
    }

    public List<SecretScanner.Finding> findings() {
        return findings;
    }
}
