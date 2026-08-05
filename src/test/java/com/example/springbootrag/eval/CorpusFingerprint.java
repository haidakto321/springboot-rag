package com.example.springbootrag.eval;

/**
 * Identifies which corpus a baseline was measured against, so a re-import is reported as a stale
 * baseline rather than as six simultaneous backend regressions.
 *
 * <p>This is a staleness check, not an integrity check: a corpus edited in place that preserves
 * both counts is not detected.
 */
public record CorpusFingerprint(long projectId, String projectName, int docCount, int chunkCount) {}
