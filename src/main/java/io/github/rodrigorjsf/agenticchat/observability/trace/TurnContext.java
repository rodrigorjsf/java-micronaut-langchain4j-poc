package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;

import java.util.Optional;

/**
 * Carries the turn's identity to every observation started inside it.
 *
 * <p><b>Not OpenTelemetry Baggage, on purpose.</b> Langfuse recommends Baggage with a
 * {@code BaggageSpanProcessor} for exactly this, and its own documentation carries the
 * reason not to: "OpenTelemetry baggage is propagated across service boundaries and to
 * third-party APIs. Do not include sensitive information … as it will be transmitted to
 * all downstream services."
 *
 * <p>Every tool in this application makes an outbound call to a public API with
 * arguments the model chose, so a {@code baggage:} header would put the user id and the
 * conversation id on requests to fifty third parties for the sake of an in-process copy.
 * A private {@link ContextKey} does the same job, propagates the same way inside the
 * process, and cannot be injected into a header by any propagator.
 */
public final class TurnContext {

    private static final ContextKey<TurnAttributes> KEY = ContextKey.named("agentic.turn.attributes");

    private TurnContext() {
    }

    public static Scope open(TurnAttributes attributes) {
        return Context.current().with(KEY, attributes).makeCurrent();
    }

    public static Optional<TurnAttributes> current() {
        return Optional.ofNullable(Context.current().get(KEY));
    }

    static Optional<TurnAttributes> from(Context context) {
        return Optional.ofNullable(context.get(KEY));
    }
}
