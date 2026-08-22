package io.github.rodrigorjsf.agenticchat.observability.trace;

/**
 * Where an observation lives, as the Scores API addresses it.
 *
 * <p>A score is not a span. It travels over {@code POST /api/public/scores} and names
 * its subject by this pair, which is the only reason the ids need to leave the tracing
 * layer at all.
 *
 * @param traceId       the 32-character hex OpenTelemetry trace id
 * @param observationId the 16-character hex OpenTelemetry span id
 */
public record ObservationRef(String traceId, String observationId) {
}
