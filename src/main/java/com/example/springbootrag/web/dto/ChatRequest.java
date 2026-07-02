package com.example.springbootrag.web.dto;

import com.example.springbootrag.chat.ChatProvider.ChatMessage;

import java.util.List;

/**
 * Client-held conversation sent each turn; the last message is the new user question.
 * {@code docIds} optionally scopes retrieval to a subset of documents (null/empty = all).
 */
public record ChatRequest(List<ChatMessage> messages, List<String> docIds) {}
