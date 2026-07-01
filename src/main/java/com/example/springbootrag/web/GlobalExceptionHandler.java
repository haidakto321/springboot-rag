package com.example.springbootrag.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps client-input errors to 400 instead of the default 500.
 * Bad search type, blank docId, out-of-range topK, etc. all surface as
 * IllegalArgumentException from the service layer and become 400 Bad Request.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ProblemDetail handleTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "file too large (max 2 MB)");
    }

    @ExceptionHandler(com.example.springbootrag.chat.ChatUnavailableException.class)
    public ProblemDetail handleChatUnavailable(com.example.springbootrag.chat.ChatUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }
}
