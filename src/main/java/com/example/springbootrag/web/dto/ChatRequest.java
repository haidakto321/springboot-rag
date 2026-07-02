package com.example.springbootrag.web.dto;

import com.example.springbootrag.chat.ChatProvider.ChatMessage;

import java.util.List;

/** Client-held conversation sent each turn; the last message is the new user question. */
public record ChatRequest(List<ChatMessage> messages) {}
