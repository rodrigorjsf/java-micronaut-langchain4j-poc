package io.github.rodrigorjsf.agenticchat.observability.trace;

import java.util.Optional;

/**
 * The one interface the rest of this application uses to be observed.
 *
 * <p>It exists so that no other package imports OpenTelemetry. Every seam — the
 * LangChain4j listeners, the {@code @Observed} interceptor, the score writer — speaks
 * this vocabulary, which means the tracing library is one implementation behind an
 * interface rather than a dependency spread across twelve classes.
 *
 * <p>An observation started while another is open becomes its child. That nesting is
 * the span hierarchy, and it comes from the OpenTelemetry context rather than from
 * anything passed by hand — verified for this codebase: LangChain4j 1.18.1 calls every
 * listener, tool executor and guardrail on the caller's thread.
 */
public interface AgentTracer {

    Observation start(String name, ObservationType type);

    /**
     * @return the observation a score would attach to, or empty when nothing is being
     * observed — which is the normal state when tracing is switched off
     */
    Optional<ObservationRef> current();
}
