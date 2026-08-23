package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(ObservationContentPolicy.class);

    private final boolean captureContent;
    private final List<ContentRedactor> redactors;

    public ObservationContentPolicy(
            @Value("${agentic.observability.capture-content:true}") boolean captureContent,
            List<ContentRedactor> redactors) {
        this.captureContent = captureContent;
        this.redactors = List.copyOf(redactors);
    }

    /**
     * @return the value to write, or {@code null} when content capture is off, when a
     * redactor dropped it, or when a redactor FAILED — the observation then writes no
     * attribute rather than an empty one
     */
    public Object capture(Object value) {
        if (!captureContent || value == null) {
            return null;
        }
        Object redacted = value;
        for (ContentRedactor redactor : redactors) {
            try {
                redacted = redactor.redact(redacted);
            } catch (RuntimeException e) {
                // A redactor is deployment-supplied code running inside a method it was
                // only supposed to watch, and this is called from ChatTurnService AFTER the
                // reply exists — an escaping exception would lose a computed answer to a
                // failure in the layer observing it. Dropping the payload is the only safe
                // direction: a redactor that threw did not establish that the value is safe.
                LOG.warn("Redactor {} failed; dropping the payload rather than the call",
                        redactor.getClass().getName(), e);
                return null;
            }
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
