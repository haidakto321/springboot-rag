package com.example.springbootrag.web.dto;

import java.util.List;

/**
 * Request body for POST /projects/{projectId}/import-wiki. {@code path} is a server-side directory.
 * {@code groups} is the access label applied to every imported page; null means the default group.
 */
public record WikiImportRequest(String path, List<String> groups) {}
