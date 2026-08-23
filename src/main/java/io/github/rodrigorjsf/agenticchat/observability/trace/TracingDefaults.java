package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The OpenTelemetry autoconfiguration properties this application chooses for itself.
 *
 * <p>Its own class so the choices are testable without starting an SDK, and so the most
 * important one is visible: <b>{@code otel.traces.exporter} defaults to {@code none}</b>.
 * OpenTelemetry's own default is {@code otlp}, which means an application that merely has the
 * SDK on its classpath opens a connection to {@code localhost:4318} and retries it. This
 * project's build is required to need no network, and a test run quietly dialling a collector
 * that is not there is exactly the kind of thing that stays unnoticed until CI has no route to
 * the host.
 *
 * <p>These are supplied as autoconfiguration DEFAULTS, so the standard {@code OTEL_*} environment
 * variables still win. An operator who knows OpenTelemetry does not have to learn this
 * application's property names to point it somewhere else.
 */
@Singleton
public class TracingDefaults {

    private final String otlpEndpoint;
    private final double sampleRate;

    public TracingDefaults(
            @Value("${agentic.observability.otlp.endpoint:}") @Nullable String otlpEndpoint,
            @Value("${agentic.observability.sample-rate:1.0}") double sampleRate) {
        this.otlpEndpoint = otlpEndpoint;
        this.sampleRate = sampleRate;
    }

    public Map<String, String> properties() {
        var properties = new LinkedHashMap<String, String>();
        boolean collectorConfigured = otlpEndpoint != null && !otlpEndpoint.isBlank();
        properties.put("otel.traces.exporter", collectorConfigured ? "otlp" : "none");
        if (collectorConfigured) {
            properties.put("otel.exporter.otlp.endpoint", otlpEndpoint);
            // Langfuse accepts HTTP only, and a collector in front of it has no reason to
            // differ. Stated rather than inherited so a gRPC default upstream cannot silently
            // produce an exporter Langfuse would refuse.
            properties.put("otel.exporter.otlp.protocol", "http/protobuf");
        }
        // Metrics follow the collector, and ONLY the collector.
        //
        // OpenTelemetry's own default here is `otlp`, and unlike the trace exporter a
        // MeterProvider dials on an INTERVAL rather than once — so the default would have
        // every `./mvnw test` run open a connection to localhost:4318 and keep retrying
        // it. The default build is required to need no network, which is why this is
        // conditional rather than simply enabled.
        //
        // With a collector configured there is somewhere to send them: the collector's
        // metrics pipeline already has an otlp receiver beside the span_metrics connector,
        // so gen_ai.client.token.usage and gen_ai.client.operation.duration reach
        // Prometheus with no further configuration. They go to the COLLECTOR and not to
        // Langfuse, which ingests OTLP traces only — and this endpoint is the collector's.
        properties.put("otel.metrics.exporter", collectorConfigured ? "otlp" : "none");
        // Logs stay Logback's job either way. Correlating them with traces is a small
        // change that has not been made; see chapter 7.
        properties.put("otel.logs.exporter", "none");

        // parentbased_traceidratio, and the reason is NOT the one that used to be written
        // here. This comment claimed a bare ratio sampler "could drop the root and keep a
        // child"; it cannot. TraceIdRatioBasedSampler.shouldSample is a pure function of the
        // trace id and the ratio — the SDK's own comment says the decision holds "even for
        // child spans" — so within one process every span of a trace already gets the same
        // answer.
        //
        // What parent-based actually adds is the remote case: it honours an incoming W3C
        // `sampled` flag instead of re-deciding with THIS service's ratio. Two services at
        // 0.5 that each decide for themselves keep a quarter of the traces whole and cut
        // three quarters somewhere in the middle. Nothing calls this application today, so
        // this is insurance — but it is the correct insurance, and the sentence that used
        // to justify it was a mechanism that does not exist.
        properties.put("otel.traces.sampler", "parentbased_traceidratio");
        properties.put("otel.traces.sampler.arg", Double.toString(sampleRate));

        // NOT the OpenTelemetry default of "tracecontext,baggage". Baggage is injected into
        // outbound request headers, and every tool in this application calls a third-party API
        // with arguments the model chose. See TurnContext for the same decision at the other end.
        properties.put("otel.propagators", "tracecontext");
        return Map.copyOf(properties);
    }
}
