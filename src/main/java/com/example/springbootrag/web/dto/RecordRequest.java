package com.example.springbootrag.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * One extracted record. {@code record} is arbitrary JSON straight from the extraction pipeline -
 * nested, per-tenant, and possibly of a document type nobody has configured.
 * {@code force} re-indexes even when nothing changed.
 */
public record RecordRequest(String docId, String docType, JsonNode record,
                            Map<String, Object> metadata, List<String> groups, Boolean force) {}
