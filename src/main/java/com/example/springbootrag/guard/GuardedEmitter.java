package com.example.springbootrag.guard;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Puts {@link AnswerGuard} in FRONT of a streamed answer instead of behind it.
 *
 * <p>Before this class, {@code /chat/stream} computed the verdict after the last token and could
 * only log "already sent to the client": the guard was a control on {@code /ask} and a report on
 * the streaming path. Two states fix that for everything decidable while the answer is still being
 * written.
 *
 * <ul>
 *   <li>HOLDING - buffer, emit nothing. An answer that never cites anything therefore reaches the
 *       client as a refusal rather than as text followed by an apology.</li>
 *   <li>PASSING - entered on the first in-range citation; emit whole sentences, and stop the
 *       stream if a later sentence cites a chunk that was never supplied.</li>
 * </ul>
 *
 * <p>It does not restate the guard's rules. Citation extraction, the {@code 1..chunkCount} bound
 * and the refusal text all come from {@link AnswerGuard}, and a test pins that the two agree on
 * the same text.
 *
 * <p>What it cannot do: a groundedness failure - an answer that cites correctly while misstating
 * what the source says - needs the whole claim, so it is only decidable after the last token. That
 * one is still a report on this path.
 */
public final class GuardedEmitter {

    private final int chunkCount;
    private final Consumer<String> sink;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder all = new StringBuilder();

    private boolean passing;
    private boolean sentAnything;
    private boolean stopped;
    private String stopReason;

    public GuardedEmitter(int chunkCount, Consumer<String> sink) {
        this.chunkCount = chunkCount;
        this.sink = sink;
    }

    /** Feeds one model token. Emits zero, one, or more pieces downstream. */
    public void accept(String token) {
        if (stopped || token == null || token.isEmpty()) {
            return;
        }
        all.append(token);
        pending.append(token);
        if (!passing) {
            tryEnterPassing();
            return;
        }
        emitCompleteSentences();
    }

    /**
     * Ends the stream and reports what the whole answer was worth. When nothing was emitted the
     * caller can replace the answer outright - and only because nothing is on the wire.
     */
    public AnswerGuard.Verdict finish() {
        if (stopped) {
            return new AnswerGuard.Verdict(false, stopReason, AnswerGuard.REFUSAL);
        }
        if (passing) {
            // Whatever is left after the last sentence boundary: the model's final token rarely
            // lands on one, and dropping the tail would silently truncate the answer.
            flushPending();
        }
        return AnswerGuard.check(all.toString(), chunkCount);
    }

    /** Whether any text reached the client - false means a refusal can still replace the answer. */
    public boolean sentAnything() {
        return sentAnything;
    }

    /**
     * The explicit refusal is a grounded outcome that carries no citation by design, so holding
     * out for one would hold forever.
     */
    private void tryEnterPassing() {
        String text = pending.toString();
        if (text.strip().startsWith(AnswerGuard.REFUSAL)) {
            passing = true;
            flushPending();
            return;
        }
        Set<Integer> cited = AnswerGuard.citations(text);
        if (cited.isEmpty()) {
            return;
        }
        for (int n : cited) {
            if (n < 1 || n > chunkCount) {
                stop("bad-citation");
                return;
            }
        }
        passing = true;
        emitCompleteSentences();
    }

    /**
     * Emits up to the last sentence boundary in the buffer, checking that sentence's citations
     * first. Boundary detection is deliberately dumb - a decimal point splits a sentence early,
     * and the only consequence is emitting in smaller pieces.
     */
    private void emitCompleteSentences() {
        int cut = lastBoundary(pending);
        if (cut < 0) {
            return;
        }
        String ready = pending.substring(0, cut + 1);
        for (int n : AnswerGuard.citations(ready)) {
            if (n < 1 || n > chunkCount) {
                // None of this sentence goes out: the good prefix has already been sent, and the
                // client must not see the fabricated claim.
                stop("bad-citation");
                return;
            }
        }
        pending.delete(0, cut + 1);
        send(ready);
    }

    private void flushPending() {
        if (pending.length() == 0) {
            return;
        }
        String rest = pending.toString();
        pending.setLength(0);
        send(rest);
    }

    private void send(String text) {
        sentAnything = true;
        sink.accept(text);
    }

    private void stop(String reason) {
        stopped = true;
        stopReason = reason;
        pending.setLength(0);
    }

    private static int lastBoundary(CharSequence s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                return i;
            }
        }
        return -1;
    }
}
