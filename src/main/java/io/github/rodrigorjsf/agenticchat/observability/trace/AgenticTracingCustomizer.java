package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.tracing.opentelemetry.OpenTelemetryBuilderCustomizer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds this application's span processors to the SDK Micronaut builds.
 *
 * <p>Through {@link OpenTelemetryBuilderCustomizer} rather than by contributing a
 * {@code SpanProcessor} bean: Micronaut's {@code DefaultOpenTelemetryFactory} injects exactly one
 * {@code SpanProcessor}, so a second bean is a startup failure and replacing the first is a fight
 * with the framework over something it is entitled to own.
 *
 * <p>Two destinations are supported and both are optional, which is the whole configuration
 * surface:
 *
 * <ul>
 *   <li>an <b>OTLP collector</b>, configured through {@link TracingDefaults}, which is where the
 *       fan-out to Langfuse, Tempo and the span metrics belongs;</li>
 *   <li>a <b>Langfuse instance directly</b>, so the simplest useful setup does not require running
 *       a collector at all.</li>
 * </ul>
 *
 * <p>With neither configured nothing is exported and nothing is dialled, which is what keeps the
 * default build free of a network.
 */
@Singleton
public class AgenticTracingCustomizer implements OpenTelemetryBuilderCustomizer {

    private static final Logger LOG = LoggerFactory.getLogger(AgenticTracingCustomizer.class);

    private final TurnAttributesSpanProcessor turnAttributes;
    private final TracingDefaults defaults;
    private final LangfuseOtlpSettings langfuse;

    public AgenticTracingCustomizer(TurnAttributesSpanProcessor turnAttributes,
                                    TracingDefaults defaults,
                                    @Nullable LangfuseOtlpSettings langfuse) {
        this.turnAttributes = turnAttributes;
        this.defaults = defaults;
        this.langfuse = langfuse;
    }

    @Override
    public void configure(AutoConfiguredOpenTelemetrySdkBuilder builder) {
        builder.addPropertiesSupplier(defaults::properties);
        builder.addTracerProviderCustomizer((tracerProvider, config) -> {
            // Before any exporting processor, because it writes attributes at span start and a
            // processor that exports on end must see them.
            tracerProvider.addSpanProcessor(turnAttributes);
            if (langfuse != null) {
                var exporter = OtlpHttpSpanExporter.builder()
                        .setEndpoint(langfuse.tracesEndpoint());
                langfuse.headers().forEach(exporter::addHeader);
                // Batched, not simple: a turn produces a dozen observations and a synchronous
                // export would put an HTTP round trip inside the request the user is waiting on.
                tracerProvider.addSpanProcessor(BatchSpanProcessor.builder(exporter.build()).build());
                LOG.info("Exporting traces directly to Langfuse at {}", langfuse.tracesEndpoint());
            }
            return tracerProvider;
        });
    }
}
