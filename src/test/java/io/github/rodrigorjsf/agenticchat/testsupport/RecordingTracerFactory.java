package io.github.rodrigorjsf.agenticchat.testsupport;

import io.github.rodrigorjsf.agenticchat.observability.trace.TurnAttributesSpanProcessor;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.opentelemetry.api.trace.Tracer;
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

    @Singleton
    @Replaces(Tracer.class)
    Tracer tracer(SdkTracerProvider tracerProvider) {
        return tracerProvider.get("agenticchat-test");
    }
}
