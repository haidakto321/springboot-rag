package com.example.springbootrag.web;

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
import java.util.List;
import java.util.Map;

/**
 * Streaming multi-turn chat. Responds with newline-delimited JSON frames:
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

    public ChatController(ChatService chatService, ObjectMapper mapper, ProjectService projectService) {
        this.chatService = chatService;
        this.mapper = mapper;
        this.projectService = projectService;
    }

    @PostMapping(value = "/chat/stream", produces = "application/x-ndjson")
    public StreamingResponseBody stream(@RequestBody ChatRequest req) {
        if (req == null || req.messages() == null || req.messages().isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        // Resolve project scope before the stream starts so any bad projectId fails fast (400/500).
        List<Long> scope = projectService.resolveScope(req.projectId(), req.group());
        return out -> {
            try {
                List<AskResponse.Source> sources =
                        chatService.chatStream(req.messages(), scope, req.docIds(), req.think(),
                                token -> writeFrame(out, Map.of("type", "token", "text", token)),
                                reasoning -> writeFrame(out, Map.of("type", "reasoning", "text", reasoning)));
                writeFrame(out, Map.of("type", "sources", "sources", sources));
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
