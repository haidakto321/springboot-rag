package com.example.springbootrag.model;

import java.time.Instant;

/**
 * One human relevance label on a retrieved chunk.
 *
 * <p>Eval-only signal: it is never read at query time and never nudges a score. The chunk is
 * identified by (docId, chunkIndex) rather than by chunk id, because re-ingesting a document
 * replaces its chunk rows and would orphan id-based labels.
 */
public record FeedbackLabel(
        long id,
        long projectId,
        String query,
        String docId,
        int chunkIndex,
        String rating,
        Instant updatedAt
) {
    public boolean relevant() {
        return "up".equals(rating);
    }
}
