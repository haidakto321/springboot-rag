package com.example.springbootrag.web.dto;

/** Request body for POST /projects/{projectId}/import-wiki. {@code path} is a server-side directory. */
public record WikiImportRequest(String path) {}
