package com.example.springbootrag.web.dto;

/**
 * One thumb on one retrieved chunk. {@code rating} is "up" or "down"; un-voting is a
 * DELETE /feedback with the same key, not a rating value.
 */
public record FeedbackRequest(
        String query,
        Long projectId,
        String docId,
        Integer chunkIndex,
        String rating
) {}
