package io.github.rodrigorjsf.agenticchat.testsupport;

import io.github.rodrigorjsf.agenticchat.observability.trace.TurnAttributesSpanProcessor;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.inject.Singleton;

/**
 * A real OpenTelemetry SDK that exports into memory instead of over a socket.
 *
 * <p>Every tracing assertion in this suite runs against this, which is what keeps the
 * default build free of a collector, a Langfuse instance and a network. Opt in with
 * {@code agentic.test.record-spans=true}.
 */
@Factory
@Requires(property = "agentic.test.record-spans", value = "true")
public class RecordingTracerFactory {

    @Singleton
    InMemorySpanExporter recordedSpans() {
        return InMemorySpanExporter.create();
    }

    @Singleton
    @Bean(preDestroy = "close")
    SdkTracerProvider tracerProvider(InMemorySpanExporter exporter, TurnAttributesSpanProcessor turnAttributes) {
        return SdkTracerProvider.builder()
                .addSpanProcessor(turnAttributes)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }

    /**
     * The whole SDK, not only a {@link Tracer}. Micronaut's HTTP client and server
     * instrumentation asks for the {@link OpenTelemetry} bean rather than for a tracer, so
     * replacing the tracer alone leaves every framework-produced span going to the real
     * SDK — which exports nowhere in tests, and the assertion then reads "the client
     * produced no span" when in fact it produced one this exporter could not see.
     */
    @Singleton
    @Replaces(OpenTelemetry.class)
    OpenTelemetry openTelemetry(SdkTracerProvider tracerProvider) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Singleton
    @Replaces(Tracer.class)
    Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("agenticchat-test");
    }
}
