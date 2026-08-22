package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.github.rodrigorjsf.agenticchat.observability.TokenUsageDetails;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * One unit of work being observed. Closing it is what sends it.
 *
 * <p>Every method returns {@code this} so a seam can describe what it did in one
 * statement, and every method tolerates a null value by writing nothing — a listener
 * should never have to guard a field the provider happened not to return.
 *
 * <p>{@link #close()} is idempotent on purpose. Langfuse v4 does not deduplicate a
 * span id it has already accepted: exporting the same observation twice creates two
 * observations and doubles every metric derived from them.
 */
public interface Observation extends AutoCloseable {

    Observation input(Object value);

    Observation output(Object value);

    /**
     * A key that stays filterable in Langfuse. Ordinary OpenTelemetry attributes do
     * not — they are filed under {@code metadata.attributes} as a catch-all.
     */
    Observation metadata(String key, Object value);

    Observation level(ObservationLevel level, String statusMessage);

    /**
     * Records the failure and marks the observation {@code ERROR}. It does not close
     * the observation: the seam that opened it still owns its lifetime.
     */
    Observation failed(Throwable error);

    /**
     * The model behind a {@code generation} or an {@code embedding}, and the settings it
     * was invoked with. Ignored by Langfuse on any other observation type.
     */
    Observation model(String modelName, Map<String, Object> parameters);

    /**
     * Token counts, already split into non-overlapping buckets. An empty set writes
     * nothing: Langfuse would otherwise record a call that used no tokens, which is a
     * different claim from a call whose usage was not reported.
     */
    Observation usage(TokenUsageDetails usage);

    /**
     * USD per usage bucket. Ingesting this stops Langfuse inferring cost from its own
     * model definitions, which would disagree with {@code agentic.llm.pricing}.
     */
    Observation cost(Map<String, BigDecimal> costDetails);

    /**
     * When the model produced its first token — the number that separates queue time
     * from generation time.
     */
    Observation completionStartedAt(Instant instant);

    /**
     * The OpenTelemetry GenAI semantic-convention attributes, for the consumers that do
     * not read {@code langfuse.*}.
     */
    Observation genAi(String system, String operationName, String requestModel);

    /**
     * Declares this observation the head of its trace.
     *
     * <p>Only the turn does this. Langfuse reads two different attributes for it depending
     * on which ingestion path the deployment runs, so both are written.
     */
    Observation asTraceRoot();

    ObservationRef ref();

    @Override
    void close();
}
