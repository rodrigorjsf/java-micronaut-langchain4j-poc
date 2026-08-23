package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;

import java.util.Optional;

/**
 * Carries the experiment's identity to every observation started inside it.
 *
 * <p><b>Not OpenTelemetry Baggage, for the same reason as {@link TurnContext}.</b> The
 * Langfuse documentation recommends Baggage and a {@code BaggageSpanProcessor} for exactly
 * this, and states the cost on the same page: "OpenTelemetry injects Baggage into outbound
 * request headers." The propagator setting controls only that header injection — a span
 * processor reading a private {@link ContextKey} produces the identical span attributes
 * with nothing on the wire, and {@link TurnAttributesSpanProcessor} already proves the
 * mechanism here.
 *
 * <p>An eval run is exactly when the reasoning bites hardest: the rows are adversarial by
 * construction, and the tools they reach still call third-party APIs.
 */
public final class ExperimentContext {

    private static final ContextKey<ExperimentAttributes> KEY =
            ContextKey.named("agentic.experiment.attributes");

    private ExperimentContext() {
    }

    /**
     * Opens the experiment level — the identity every item trace in the run shares.
     */
    public static Scope open(ExperimentAttributes attributes) {
        return Context.current().with(KEY, attributes).makeCurrent();
    }

    /**
     * Opens the item level, so the children of an item root are attributable to the row
     * that produced them.
     *
     * <p>Called after the root span exists, because {@code rootObservationId} is that
     * span's id. With no experiment open this is a no-op scope rather than an error: a
     * test that runs the same code outside a run should behave the same, not throw.
     */
    public static Scope openItem(String rootObservationId, String itemId, String itemVersion) {
        var current = Context.current().get(KEY);
        if (current == null) {
            return Scope.noop();
        }
        return Context.current()
                .with(KEY, current.withItem(itemId, itemVersion, rootObservationId))
                .makeCurrent();
    }

    public static Optional<ExperimentAttributes> current() {
        return Optional.ofNullable(Context.current().get(KEY));
    }

    static Optional<ExperimentAttributes> from(Context context) {
        return Optional.ofNullable(context.get(KEY));
    }
}
