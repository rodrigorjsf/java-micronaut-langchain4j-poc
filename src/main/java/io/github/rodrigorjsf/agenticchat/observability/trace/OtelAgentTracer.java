package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import io.github.rodrigorjsf.agenticchat.observability.TokenUsageDetails;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The OpenTelemetry implementation of {@link AgentTracer}.
 *
 * <p>Turning tracing off is not this class's job. With no SDK configured the injected
 * {@link Tracer} is OpenTelemetry's own no-op, every span is invalid, and the
 * attribute writes below cost a virtual call each. That is why there is no
 * {@code if (enabled)} anywhere here, and no second no-op implementation to keep in
 * step with this one.
 */
@Singleton
@Requires(missingBeans = AgentTracer.class)
public class OtelAgentTracer implements AgentTracer {

    private final Tracer tracer;
    private final ObservationJson json;

    public OtelAgentTracer(Tracer tracer, ObservationJson json) {
        this.tracer = tracer;
        this.json = json;
    }

    @Override
    public Observation start(String name, ObservationType type) {
        var span = tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAttribute(LangfuseAttributes.OBSERVATION_TYPE, type.wireValue());
        return new SpanObservation(span, span.makeCurrent(), json);
    }

    @Override
    public Optional<ObservationRef> current() {
        var context = Span.current().getSpanContext();
        return context.isValid()
                ? Optional.of(new ObservationRef(context.getTraceId(), context.getSpanId()))
                : Optional.empty();
    }

    private static final class SpanObservation implements Observation {

        private final Span span;
        private final Scope scope;
        private final ObservationJson json;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SpanObservation(Span span, Scope scope, ObservationJson json) {
            this.span = span;
            this.scope = scope;
            this.json = json;
        }

        @Override
        public Observation input(Object value) {
            return write(LangfuseAttributes.OBSERVATION_INPUT.getKey(), json.write(value));
        }

        @Override
        public Observation output(Object value) {
            return write(LangfuseAttributes.OBSERVATION_OUTPUT.getKey(), json.write(value));
        }

        @Override
        public Observation metadata(String key, Object value) {
            if (value == null) {
                return this;
            }
            String text = value instanceof String string ? string : json.write(value);
            return write(LangfuseAttributes.observationMetadata(key).getKey(), text);
        }

        @Override
        public Observation level(ObservationLevel level, String statusMessage) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, level.name());
            return write(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE.getKey(), statusMessage);
        }

        @Override
        public Observation failed(Throwable error) {
            // recordException as well as the Langfuse attributes: the same span is read
            // by Tempo, which knows nothing about langfuse.* and everything about events.
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, message(error));
            return level(ObservationLevel.ERROR, message(error));
        }

        @Override
        public Observation model(String modelName, Map<String, Object> parameters) {
            write(LangfuseAttributes.MODEL_NAME.getKey(), modelName);
            return parameters == null || parameters.isEmpty()
                    ? this
                    : write(LangfuseAttributes.MODEL_PARAMETERS.getKey(), json.write(parameters));
        }

        @Override
        public Observation usage(TokenUsageDetails usage) {
            return usage == null || usage.isEmpty()
                    ? this
                    : write(LangfuseAttributes.USAGE_DETAILS.getKey(), json.write(usage.buckets()));
        }

        @Override
        public Observation cost(Map<String, BigDecimal> costDetails) {
            return costDetails == null || costDetails.isEmpty()
                    ? this
                    : write(LangfuseAttributes.COST_DETAILS.getKey(), json.write(costDetails));
        }

        @Override
        public Observation completionStartedAt(Instant instant) {
            return instant == null
                    ? this
                    : write(LangfuseAttributes.COMPLETION_START_TIME.getKey(),
                            DateTimeFormatter.ISO_INSTANT.format(instant));
        }

        @Override
        public Observation genAi(String system, String operationName, String requestModel) {
            write(GenAiAttributes.SYSTEM.getKey(), system);
            write(GenAiAttributes.OPERATION_NAME.getKey(), operationName);
            return write(GenAiAttributes.REQUEST_MODEL.getKey(), requestModel);
        }

        @Override
        public ObservationRef ref() {
            var context = span.getSpanContext();
            return new ObservationRef(context.getTraceId(), context.getSpanId());
        }

        @Override
        public void close() {
            // Idempotent because Langfuse v4 does not deduplicate a span id it has
            // already accepted: a second export is a second observation in the UI.
            if (closed.compareAndSet(false, true)) {
                scope.close();
                span.end();
            }
        }

        private Observation write(String key, String value) {
            if (value != null && !value.isBlank()) {
                span.setAttribute(key, value);
            }
            return this;
        }

        private static String message(Throwable error) {
            // A message can be null, and an exception type is more useful than an
            // empty status anyway.
            return error.getMessage() == null || error.getMessage().isBlank()
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
        }
    }
}
