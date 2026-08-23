package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The observation SPI, asserted through exported spans.
 *
 * <p>The seam is {@link AgentTracer} and the observable is what an OTLP exporter
 * would send — never the tracer's internals. That is deliberate: Langfuse reads the
 * exported span and nothing else, so a test that inspected the builder would pass
 * while the integration was blind.
 */
class AgentTracerTest {

    private InMemorySpanExporter exported;
    private SdkTracerProvider tracerProvider;
    private AgentTracer tracer;

    @BeforeEach
    void setUp() {
        exported = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exported))
                .build();
        tracer = new OtelAgentTracer(tracerProvider.get("test"), ObservationJson.compact());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    @DisplayName("an observation carries the Langfuse observation type")
    void observationCarriesItsType() {
        try (var observation = tracer.start("triage", ObservationType.CHAIN)) {
            assertThat(observation).isNotNull();
        }

        assertThat(exported.getFinishedSpanItems()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("triage");
            assertThat(span.getAttributes().asMap())
                    .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "chain");
        });
    }

    @Test
    @DisplayName("input and output are written as JSON on the observation")
    void inputAndOutputAreSerialised() {
        try (var observation = tracer.start("turn", ObservationType.AGENT)) {
            observation.input(Map.of("message", "bom dia"));
            observation.output("Bom dia!");
        }

        var span = exported.getFinishedSpanItems().getFirst();
        assertThat(span.getAttributes().asMap())
                .containsEntry(LangfuseAttributes.OBSERVATION_INPUT, "{\"message\":\"bom dia\"}")
                .containsEntry(LangfuseAttributes.OBSERVATION_OUTPUT, "\"Bom dia!\"");
    }

    @Test
    @DisplayName("an observation started inside another is its child")
    void childObservationNestsUnderItsParent() {
        try (var parent = tracer.start("turn", ObservationType.AGENT)) {
            try (var child = tracer.start("weather", ObservationType.TOOL)) {
                assertThat(child).isNotNull();
            }
        }

        var spans = exported.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        var child = spans.stream().filter(s -> s.getName().equals("weather")).findFirst().orElseThrow();
        var parent = spans.stream().filter(s -> s.getName().equals("turn")).findFirst().orElseThrow();
        assertThat(child.getParentSpanId()).isEqualTo(parent.getSpanId());
        assertThat(child.getTraceId()).isEqualTo(parent.getTraceId());
    }

    @Test
    @DisplayName("closing an observation twice exports it once")
    void anObservationIsExportedExactlyOnce() {
        var observation = tracer.start("turn", ObservationType.AGENT);
        observation.close();
        observation.close();

        // Langfuse v4 does not deduplicate a re-exported span id: a second export is a
        // second observation in the UI, with inflated counts behind it.
        assertThat(exported.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    @DisplayName("a failure sets the ERROR level and the status message Langfuse reads")
    void aFailureIsRecordedAtErrorLevel() {
        try (var observation = tracer.start("weather", ObservationType.TOOL)) {
            observation.failed(new IllegalStateException("upstream refused"));
        }

        var span = exported.getFinishedSpanItems().getFirst();
        assertThat(span.getAttributes().asMap())
                .containsEntry(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR")
                .containsEntry(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, "upstream refused");
    }

    @Test
    @DisplayName("the current observation reference is the id pair a score is attached to")
    void theCurrentReferenceIdentifiesTheObservation() {
        assertThat(tracer.current()).isEmpty();

        try (var observation = tracer.start("turn", ObservationType.AGENT)) {
            var ref = tracer.current().orElseThrow();
            assertThat(ref.traceId()).hasSize(32);
            assertThat(ref.observationId()).hasSize(16);
        }
    }

    @Test
    @DisplayName("metadata is prefixed so Langfuse keeps it filterable")
    void metadataIsFilterable() {
        try (var observation = tracer.start("turn", ObservationType.AGENT)) {
            observation.metadata("outcome", "ANSWERED");
        }

        assertThat(exported.getFinishedSpanItems().getFirst().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.observationMetadata("outcome"), "ANSWERED");
    }

    @Test
    @DisplayName("a null or blank value is not written at all")
    void absentValuesAreNotWritten() {
        try (var observation = tracer.start("turn", ObservationType.AGENT)) {
            observation.input(null);
            observation.metadata("skill", null);
        }

        var attributes = exported.getFinishedSpanItems().getFirst().getAttributes().asMap();
        assertThat(attributes.keySet().stream().map(Object::toString))
                .doesNotContain(LangfuseAttributes.OBSERVATION_INPUT.getKey());
        assertThat(List.copyOf(attributes.keySet()).stream().map(Object::toString))
                .doesNotContain(LangfuseAttributes.observationMetadata("skill").getKey());
    }
}
