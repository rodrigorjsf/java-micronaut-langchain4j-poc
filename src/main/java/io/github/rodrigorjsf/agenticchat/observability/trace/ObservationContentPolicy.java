package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * Decides whether an observation may carry the text that passed through it.
 *
 * <p>The inputs and outputs of this application are a user's message, the system prompt
 * and whatever a public API returned. Sending them to a tracing backend is the entire
 * point of having one — a trace with no content shows the shape of a turn and nothing
 * about it — and it is also the one place where observability becomes a data-protection
 * decision. So it is a switch with a default, not an assumption.
 *
 * <p>Redactors are a list because the useful ones are domain-specific and this class must
 * not grow to know about all of them. None ship by default; a deployment that needs to
 * mask an identifier contributes a bean.
 */
@Singleton
public class ObservationContentPolicy {

    private final boolean captureContent;
    private final List<ContentRedactor> redactors;

    public ObservationContentPolicy(
            @Value("${agentic.observability.capture-content:true}") boolean captureContent,
            List<ContentRedactor> redactors) {
        this.captureContent = captureContent;
        this.redactors = List.copyOf(redactors);
    }

    /**
     * @return the value to write, or {@code null} when content capture is off — the
     * observation then writes no attribute rather than an empty one
     */
    public Object capture(Object value) {
        if (!captureContent || value == null) {
            return null;
        }
        Object redacted = value;
        for (ContentRedactor redactor : redactors) {
            redacted = redactor.redact(redacted);
            if (redacted == null) {
                return null;
            }
        }
        return redacted;
    }

    public boolean captureContent() {
        return captureContent;
    }
}
