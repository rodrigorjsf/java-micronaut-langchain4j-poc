package io.github.rodrigorjsf.agenticchat.testsupport;

import io.github.rodrigorjsf.agenticchat.observability.TokenUsageDetails;
import io.github.rodrigorjsf.agenticchat.observability.trace.AgentTracer;
import io.github.rodrigorjsf.agenticchat.observability.trace.Observation;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationLevel;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationRef;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationType;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link AgentTracer} that keeps every observation in memory instead of exporting a span.
 *
 * <p>This is the trajectory capture of the scenario suite, and it adds no production seam:
 * {@code OtelAgentTracer} is {@code @Requires(missingBeans = AgentTracer.class)}, so a bean of
 * this class replaces it and every existing listener — the tool listener above all — writes
 * here. Opt in with {@code agentic.test.recording-agent-tracer=true}.
 *
 * <p>Concurrent collections on purpose: the trip-briefing workflow runs sub-agents in parallel,
 * and their tool calls land here from two threads at once.
 */
@Singleton
@Requires(property = "agentic.test.recording-agent-tracer", value = "true")
public class RecordingAgentTracer implements AgentTracer {

    /**
     * One observation as the seam that opened it described it.
     *
     * @param input  the value passed to {@link Observation#input}, or {@code null}
     * @param output the value passed to {@link Observation#output}, or {@code null}
     * @param usage  the value passed to {@link Observation#usage}, or {@code null}
     * @param cost   the value passed to {@link Observation#cost}, or {@code null}
     * @param error  the value passed to {@link Observation#failed}, or {@code null}
     */
    public record Recorded(String name, ObservationType type, Object input, Object output,
                           TokenUsageDetails usage, Map<String, BigDecimal> cost, Throwable error) {
    }

    private final List<RecordingObservation> observations = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public Observation start(String name, ObservationType type) {
        var observation = new RecordingObservation(name, type,
                new ObservationRef("recording", "recording-" + ids.incrementAndGet()));
        observations.add(observation);
        return observation;
    }

    /** Nothing here is a real span, so there is nothing a score could attach to. */
    @Override
    public Optional<ObservationRef> current() {
        return Optional.empty();
    }

    /** Every observation started since the last {@link #reset()}, in start order. */
    public List<Recorded> recorded() {
        return observations.stream()
                .map(o -> new Recorded(o.name, o.type, o.input, o.output, o.usage, o.cost, o.error))
                .toList();
    }

    public void reset() {
        observations.clear();
    }

    private static final class RecordingObservation implements Observation {

        private final String name;
        private final ObservationType type;
        private final ObservationRef ref;
        private volatile Object input;
        private volatile Object output;
        private volatile TokenUsageDetails usage;
        private volatile Map<String, BigDecimal> cost;
        private volatile Throwable error;

        private RecordingObservation(String name, ObservationType type, ObservationRef ref) {
            this.name = name;
            this.type = type;
            this.ref = ref;
        }

        @Override
        public Observation input(Object value) {
            this.input = value;
            return this;
        }

        @Override
        public Observation output(Object value) {
            this.output = value;
            return this;
        }

        @Override
        public Observation metadata(String key, Object value) {
            return this;
        }

        @Override
        public Observation level(ObservationLevel level, String statusMessage) {
            return this;
        }

        @Override
        public Observation failed(Throwable error) {
            this.error = error;
            return this;
        }

        @Override
        public Observation model(String modelName, Map<String, Object> parameters) {
            return this;
        }

        @Override
        public Observation usage(TokenUsageDetails usage) {
            this.usage = usage;
            return this;
        }

        @Override
        public Observation cost(Map<String, BigDecimal> costDetails) {
            this.cost = costDetails;
            return this;
        }

        @Override
        public Observation completionStartedAt(Instant instant) {
            return this;
        }

        @Override
        public Observation genAi(String providerName, String operationName, String requestModel) {
            return this;
        }

        @Override
        public Observation genAiResponse(String responseModel, String finishReason) {
            return this;
        }

        @Override
        public Observation experimentItem(String itemId, Object expectedOutput) {
            return this;
        }

        @Override
        public Observation asTraceRoot() {
            return this;
        }

        @Override
        public ObservationRef ref() {
            return ref;
        }

        @Override
        public void close() {
        }
    }
}
