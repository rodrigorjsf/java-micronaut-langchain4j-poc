package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this application tells the OpenTelemetry SDK when nobody has configured anything.
 */
class TracingDefaultsTest {

    private static final Map<String, Object> CREDENTIALS = Map.of(
            "agentic.llm.credentials.google-api-key", "fake",
            "agentic.llm.credentials.openai-api-key", "fake");

    private static Map<String, Object> with(String key, Object value) {
        var config = new HashMap<String, Object>(CREDENTIALS);
        config.put(key, value);
        return config;
    }

    @Test
    @DisplayName("nothing is exported and nothing is dialled until an endpoint is configured")
    void nothingIsExportedByDefault() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var properties = ctx.getBean(TracingDefaults.class).properties();

            // OpenTelemetry's own default here is "otlp", which opens a connection to
            // localhost:4318 and retries it. This build is required to need no network.
            assertThat(properties)
                    .containsEntry("otel.traces.exporter", "none")
                    .containsEntry("otel.metrics.exporter", "none")
                    .containsEntry("otel.logs.exporter", "none");
        }
    }

    @Test
    @DisplayName("a configured collector switches the OTLP exporter on, over HTTP")
    void aCollectorEndpointTurnsExportOn() {
        try (var ctx = ApplicationContext.run(
                with("agentic.observability.otlp.endpoint", "http://otel-collector:4318"))) {

            assertThat(ctx.getBean(TracingDefaults.class).properties())
                    .containsEntry("otel.traces.exporter", "otlp")
                    .containsEntry("otel.exporter.otlp.endpoint", "http://otel-collector:4318")
                    // Langfuse does not support gRPC, so a gRPC default upstream must not be
                    // able to produce an exporter it would refuse.
                    .containsEntry("otel.exporter.otlp.protocol", "http/protobuf");
        }
    }

    @Test
    @DisplayName("sampling is parent-based and trace-level, so a root is never dropped alone")
    void samplingIsParentBased() {
        try (var ctx = ApplicationContext.run(with("agentic.observability.sample-rate", "0.2"))) {
            assertThat(ctx.getBean(TracingDefaults.class).properties())
                    .containsEntry("otel.traces.sampler", "parentbased_traceidratio")
                    .containsEntry("otel.traces.sampler.arg", "0.2");
        }
    }

    @Test
    @DisplayName("baggage is not propagated, so no turn context can ride an outbound header")
    void baggageIsNotAPropagator() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            // The OpenTelemetry default is "tracecontext,baggage". Every tool here calls a
            // third-party API with arguments the model chose.
            assertThat(ctx.getBean(TracingDefaults.class).properties())
                    .containsEntry("otel.propagators", "tracecontext");
        }
    }
}
