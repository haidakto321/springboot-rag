package com.example.springbootrag.web;

import com.example.springbootrag.security.CurrentUser;
import com.example.springbootrag.security.SearchContext;
import com.example.springbootrag.service.ChatService;
import com.example.springbootrag.service.ProjectService;
import com.example.springbootrag.web.dto.AskResponse;
import com.example.springbootrag.web.dto.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming multi-turn chat. Responds with newline-delimited JSON frames:
 *   {"type":"route","route":"search"}  - which path is answering, before anything else
 *   {"type":"filter","applied":{...},"widened":bool}  - what query understanding decided
 *   {"type":"token","text":...}  - one per streamed delta
 *   {"type":"sources","sources":[...]}  - citation chunks
 *   {"type":"done"}  - normal end
 *   {"type":"error","message":...}  - failure mid-stream
 * Bad requests (empty body) fail fast with 400 before the stream starts.
 */
@RestController
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper mapper;
    private final ProjectService projectService;
    private final CurrentUser currentUser;

    public ChatController(ChatService chatService, ObjectMapper mapper, ProjectService projectService,
                          CurrentUser currentUser) {
        this.chatService = chatService;
        this.mapper = mapper;
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    @PostMapping(value = "/chat/stream", produces = "application/x-ndjson")
    public StreamingResponseBody stream(@RequestBody ChatRequest req) {
        if (req == null || req.messages() == null || req.messages().isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        // Resolve project scope before the stream starts so any bad projectId fails fast (400/500).
        List<Long> scope = projectService.resolveScope(req.projectId(), req.group());
        // The identity MUST be captured on the request thread: the body below runs on an async
        // thread where the SecurityContext thread-local is no longer populated, and resolving it
        // there would either fail or, worse, pick up whatever that pooled thread last held.
        SearchContext ctx = currentUser.context();
        // Parsed here, on the request thread, so a malformed filter is a clean 400 rather than an
        // error frame inside an already-committed 200 response.
        com.example.springbootrag.repository.MetadataFilter filter =
                SearchController.metadataFilter(req.docType(), req.filters());
        return out -> {
            try {
                ChatService.StreamOutcome outcome =
                        chatService.chatStream(ctx, req.messages(), scope, req.docIds(), req.think(),
                                filter,
                                // First frame of all: an answer with no citations is normal on the
                                // chitchat and aggregate routes, and alarming on the search one.
                                route -> writeFrame(out, Map.of("type", "route", "route", route)),
                                // Emitted before the first token: a narrowed search has to be
                                // visible while the answer is being read, not after it.
                                applied -> {
                                    Map<String, Object> frame = new LinkedHashMap<>();
                                    frame.put("type", "filter");
                                    frame.putAll(applied);
                                    writeFrame(out, frame);
                                },
                                // Tokens are held until the answer cites a source, so the pane
                                // stays empty for a moment. Unexplained, that reads as a hang.
                                () -> writeFrame(out, Map.of("type", "verifying")),
                                token -> writeFrame(out, Map.of("type", "token", "text", token)),
                                reasoning -> writeFrame(out, Map.of("type", "reasoning", "text", reasoning)));
                writeFrame(out, Map.of("type", "sources", "sources", outcome.sources()));
                // Hands the client the id of the trace row for this answer, so the debug view can
                // show exactly the request the user is looking at rather than "the latest one".
                writeFrame(out, Map.of("type", "trace", "requestId", outcome.requestId().toString()));
                // The tokens are already on the wire, so a failed grounding check can only be
                // reported, not applied. The UI turns this into a warning on the answer.
                if (!outcome.verdict().allowed()) {
                    writeFrame(out, Map.of("type", "guard", "reason", outcome.verdict().reason()));
                }
                writeFrame(out, Map.of("type", "done"));
            } catch (Exception e) {
                // Response is already committed (200), so report the failure as a frame.
                writeFrameQuietly(out, Map.of("type", "error",
                        "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        };
    }

    private void writeFrame(OutputStream out, Map<String, ?> frame) {
        try {
            out.write(mapper.writeValueAsBytes(frame));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeFrameQuietly(OutputStream out, Map<String, ?> frame) {
        try {
            writeFrame(out, frame);
        } catch (RuntimeException ignored) {
            // client likely disconnected; nothing more we can do
        }
    }
}
