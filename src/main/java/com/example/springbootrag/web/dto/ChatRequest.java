package com.example.springbootrag.web.dto;

import com.example.springbootrag.chat.ChatProvider.ChatMessage;

import java.util.List;

/**
 * Client-held conversation sent each turn; the last message is the new user question.
 * {@code docIds} optionally scopes retrieval to a subset of documents (null/empty = all).
 * {@code projectId} optionally scopes retrieval to a specific project (null = default project).
 * {@code group} when true, expands scope to all projects in the same group as {@code projectId}.
 * {@code think} when true, asks the model to reason first and streams that reasoning separately.
 * {@code docType} and {@code filters} narrow retrieval by extracted record metadata; the filter is
 * a caller preference and composes with - never replaces - the caller's access labels.
 */
public record ChatRequest(List<ChatMessage> messages, List<String> docIds, Long projectId,
                          boolean group, boolean think, String docType, String filters) {}
