package com.example.springbootrag.chat;

/** Local chat model unreachable or returned garbage. Maps to HTTP 503; search paths stay usable. */
public class ChatUnavailableException extends RuntimeException {
    public ChatUnavailableException(String message) {
        super(message);
    }
    public ChatUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
