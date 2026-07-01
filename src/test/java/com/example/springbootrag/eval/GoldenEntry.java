package com.example.springbootrag.eval;

/**
 * One eval case: a question and where the right answer lives.
 * expectedHeadingPath is optional (null = any chunk of the doc counts as a hit).
 */
public record GoldenEntry(String question, String expectedDocId, String expectedHeadingPath) {}
