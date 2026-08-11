package com.example.springbootrag.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * A held document as the API shows it.
 *
 * <p>The raw text is deliberately NOT included. Listing the pen must not hand back the content
 * quarantine exists to withhold - the findings say what kind of secret was seen, with the value
 * masked, and that is enough to decide whether to release it.
 */
public record QuarantineView(String docId, String origin, String sourceFile, String docType,
                             List<String> allowedGroups, Object findings, Instant heldAt) {}
