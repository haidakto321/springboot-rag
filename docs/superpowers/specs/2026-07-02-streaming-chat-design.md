# Design: Unit B - Streaming Chat

Date: 2026-07-02
Scope: Streaming, multi-turn chat for the Ask surface. Merges "streaming answers" +
"conversational follow-up" from the roadmap. Backend + frontend. No new dependencies
(Spring MVC + RestClient already present; Ollama already supports streaming).

## Goal

Turn the single-shot `/ask` into a chat: answers stream in token-by-token, and the model
remembers prior turns so follow-up questions work.

## Decisions (settled with user)

1. **Memory = client-held.** Browser keeps the thread and sends it each turn. Server is
   stateless - no session store, no DB.
2. **History cap.** Client sends only the last **10 messages**; server re-enforces the same
   cap as a guard. Turn-count based, not token-counting. Full thread still shown in UI.
3. **Retrieval = latest user message only.** Still a real session chat because the model
   receives the whole (trimmed) thread. Vague-follow-up handling via query condensation is
   deferred to the roadmap ("condense-question retrieval").
4. **Transport = NDJSON stream** over `StreamingResponseBody` (POST + `fetch` body reader),
   not SSE - simpler for a POST with a request body.

## Backend

### ChatProvider - add streaming
```java
public interface ChatProvider {
    String chat(String systemPrompt, String userPrompt);              // unchanged
    void chatStream(String systemPrompt, List<ChatMessage> messages,  // new
                    java.util.function.Consumer<String> onToken);
    record ChatMessage(String role, String content) {}
}
```
`OllamaChatProvider.chatStream`: POST `/api/chat` with `stream:true`, `think:false`. Read the
response body as a stream (RestClient `.exchange(...)` exposing the `InputStream`), parse each
NDJSON line, pull `message.content`, call `onToken` per delta. Stop on `"done": true`. Map
transport failures to `ChatUnavailableException` (same as `chat`).

### ChatService (new) - RAG + prompt assembly
```java
StreamResult chatStream(List<ChatMessage> history, Consumer<String> onToken);
record StreamResult(List<AskResponse.Source> sources) {}   // returned after streaming
```
Steps:
1. Trim `history` to last 10 messages; require the last message role == "user"; reject blank.
2. Retrieve: `searchService.search("rerank", lastUserContent, props.getContextChunks())`.
3. If no hits: emit a fixed "no relevant chunks" message via `onToken`, return empty sources.
4. Build the Ollama message list: `[system]` + prior turns (verbatim) + a final user message
   built by `buildUserPrompt(lastQuestion, hits)` (reuse AskService's numbered-context format;
   extract the helper to shared code).
5. Call `chat.chatStream(...)`, forwarding tokens to `onToken`.
6. Return the `sources` (chunk citations, same shape as `/ask`).

`SYSTEM_PROMPT` reused from AskService (extract to a shared constant).

### Endpoint - ChatController
```java
@PostMapping(value = "/chat/stream", produces = "application/x-ndjson")
StreamingResponseBody stream(@RequestBody ChatRequest req)
```
`ChatRequest = record ChatRequest(List<ChatProvider.ChatMessage> messages)`.

The `StreamingResponseBody` writes newline-delimited JSON objects, flushing each:
- `{"type":"sources","sources":[{index,docId,headingPath,score,content}, ...]}` - first,
  right after retrieval (citations are known before generation).
- `{"type":"token","text":"..."}` - one per streamed delta.
- `{"type":"done"}` - last.
- `{"type":"error","message":"..."}` - on failure (e.g. `ChatUnavailableException`), instead
  of `done`.

Body-size guard: rely on Spring's default and add an explicit check that `messages` is
non-empty and not absurdly large (e.g. reject > 50 messages before trimming).

## Frontend - Ask screen becomes a chat thread

Replace the single question box + one answer block with a thread:
- `chatMessages = [{role:'user'|'assistant', content, sources?}]` in JS (the client memory).
- **Thread view**: bubbles - user right-aligned, assistant left-aligned. Assistant bubble
  shows streaming text; citation chips render under it once the `sources` frame arrives.
- **Composer**: text input + Send at the bottom. Enter submits. "New chat" button clears
  `chatMessages`.
- **Send flow**:
  1. Push `{role:'user', content}`; render; clear input.
  2. Push an empty `{role:'assistant', content:''}`; render a live bubble.
  3. `POST /chat/stream` with `{ messages: chatMessages.slice(-10) mapped to {role,content} }`.
  4. Read `response.body` with a reader; split on newlines; `JSON.parse` each line; dispatch:
     `sources` -> stash on the assistant message; `token` -> append text, update bubble;
     `error` -> show error in the bubble; `done` -> finalize (render chips).
  5. Disable Send while streaming.
- Citation chips reuse the existing `.chip` style + `toggleSource` inline-content behavior.

The old `GET /ask` endpoint stays (backward compatible) but the UI no longer calls it.

## Testing

- `OllamaChatProviderTest`: add a streaming case with `MockRestServiceServer` returning a
  multi-line NDJSON body; assert `onToken` receives the deltas in order and stops on `done`.
- `ChatServiceTest` (fake ChatProvider capturing the assembled message list): asserts trimming
  to 10, system prompt present, prior turns preserved, context block built from retrieved hits,
  sources returned; no-hits path emits the fixed message and empty sources.
- Endpoint: MockMvc async test on `/chat/stream` asserting the NDJSON frames include a
  `sources` line, at least one `token` line, and a terminal `done` line.
- Frontend: manual - stream visibly types out; follow-up question uses prior context; "New
  chat" resets; error (stop Ollama) shows an error bubble.

## Out of scope (roadmap)
- Condense-question retrieval for vague follow-ups.
- Server-side session persistence / multi-device threads.
- Token-based (vs turn-count) history trimming.
