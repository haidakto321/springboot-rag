package com.example.springbootrag.web.dto;

import java.util.List;

/** {@code status} is one of: indexed, metadata-refreshed, skipped. */
public record RecordResponse(String docId, int chunksStored, String status, List<String> warnings) {}
