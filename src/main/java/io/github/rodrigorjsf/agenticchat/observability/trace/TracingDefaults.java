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
        // Metrics and logs are Micrometer's and Logback's jobs in this application. Leaving the
        // OpenTelemetry defaults in place would add two more exporters dialling localhost.
        properties.put("otel.metrics.exporter", "none");
        properties.put("otel.logs.exporter", "none");

        // Sampling is a trace-level decision taken at the root, which is what Langfuse needs:
        // it requires a root span to build a trace, so a sampler that could drop the root and
        // keep a child would produce orphans.
        properties.put("otel.traces.sampler", "parentbased_traceidratio");
        properties.put("otel.traces.sampler.arg", Double.toString(sampleRate));

        // NOT the OpenTelemetry default of "tracecontext,baggage". Baggage is injected into
        // outbound request headers, and every tool in this application calls a third-party API
        // with arguments the model chose. See TurnContext for the same decision at the other end.
        properties.put("otel.propagators", "tracecontext");
        return Map.copyOf(properties);
    }
}
