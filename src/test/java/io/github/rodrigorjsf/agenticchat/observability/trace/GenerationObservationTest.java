package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.github.rodrigorjsf.agenticchat.observability.TokenUsageDetails;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fields only a {@code generation} or an {@code embedding} may carry.
 *
 * <p>Langfuse tracks usage and cost on those two observation types and on no other, so
 * these attributes are what make the LLM bill decomposable in the UI rather than a
 * number in a Prometheus counter with no trace behind it.
 */
class GenerationObservationTest {

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
    @DisplayName("the model name and its invocation parameters are written")
    void modelAndParametersAreWritten() {
        try (var generation = tracer.start("agent", ObservationType.GENERATION)) {
            generation.model("gemini-3.1-flash-lite", Map.of("temperature", 0.3));
        }

        assertThat(exported.getFinishedSpanItems().getFirst().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.MODEL_NAME, "gemini-3.1-flash-lite")
                .containsEntry(LangfuseAttributes.MODEL_PARAMETERS, "{\"temperature\":0.3}");
    }

    @Test
    @DisplayName("usage details are written as the exclusive buckets Langfuse expects")
    void usageIsWrittenAsExclusiveBuckets() {
        var usage = new TokenUsageDetails(new java.util.LinkedHashMap<>(Map.of("input", 86L)));

        try (var generation = tracer.start("agent", ObservationType.GENERATION)) {
            generation.usage(usage);
        }

        assertThat(exported.getFinishedSpanItems().getFirst().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.USAGE_DETAILS, "{\"input\":86}");
    }

    @Test
    @DisplayName("the OpenTelemetry usage attributes are written beside the Langfuse ones")
    void theGenAiUsageFamilyIsWrittenToo() {
        // The two conventions fold differently. gen_ai.usage.input_tokens is EVERY prompt
        // token, cache reads included; Langfuse's buckets are mutually exclusive and split
        // the same tokens across input and input_cached_tokens. Both are written from the
        // one TokenUsageDetails, so they cannot disagree.
        var usage = new TokenUsageDetails(new java.util.LinkedHashMap<String, Long>(Map.of(
                "input", 100L, "input_cached_tokens", 900L,
                "output", 50L, "output_reasoning_tokens", 200L)));
        try (var generation = tracer.start("agent", ObservationType.GENERATION)) {
            generation.usage(usage);
        }

        var attributes = exported.getFinishedSpanItems().getFirst().getAttributes();
        assertThat(attributes.get(GenAiAttributes.USAGE_INPUT_TOKENS)).isEqualTo(1000L);
        assertThat(attributes.get(GenAiAttributes.USAGE_OUTPUT_TOKENS)).isEqualTo(250L);
        // And the Langfuse family is untouched by their presence.
        assertThat(attributes.asMap())
                .containsEntry(LangfuseAttributes.USAGE_DETAILS, ObservationJson.compact().write(usage.buckets()));
    }

    @Test
    @DisplayName("a call that reported no usage writes neither convention")
    void noUsageWritesNeitherConvention() {
        try (var generation = tracer.start("agent", ObservationType.GENERATION)) {
            generation.usage(TokenUsageDetails.of(null));
        }

        var attributes = exported.getFinishedSpanItems().getFirst().getAttributes();
        assertThat(attributes.get(GenAiAttributes.USAGE_INPUT_TOKENS)).isNull();
        assertThat(attributes.get(GenAiAttributes.USAGE_OUTPUT_TOKENS)).isNull();
    }

    @Test
    @DisplayName("cost details are ingested rather than left for Langfuse to infer")
    void costIsIngested() {
        try (var generation = tracer.start("agent", ObservationType.GENERATION)) {
            generation.cost(Map.of("total", new BigDecimal("0.000123")));
        }

        assertThat(exported.getFinishedSpanItems().getFirst().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.COST_DETAILS, "{\"total\":0.000123}");
    }

    @Test
    @DisplayName("empty usage and empty cost write no attribute at all")
    void nothingIsWrittenForAnUnpricedUnmeteredCall() {
        try (var generation = tracer.start("agent", ObservationType.GENERATION)) {
            generation.usage(TokenUsageDetails.of(null));
            generation.cost(Map.of());
        }

        var keys = exported.getFinishedSpanItems().getFirst().getAttributes().asMap().keySet()
                .stream().map(Object::toString).toList();
        assertThat(keys).doesNotContain(
                LangfuseAttributes.USAGE_DETAILS.getKey(), LangfuseAttributes.COST_DETAILS.getKey());
    }

    @Test
    @DisplayName("the completion start time is written as an ISO-8601 instant")
    void completionStartTimeIsIso8601() {
        try (var generation = tracer.start("agent", ObservationType.GENERATION)) {
            generation.completionStartedAt(Instant.parse("2026-08-22T12:00:00Z"));
        }

        assertThat(exported.getFinishedSpanItems().getFirst().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.COMPLETION_START_TIME, "2026-08-22T12:00:00Z");
    }

    @Test
    @DisplayName("the GenAI semantic-convention attributes travel beside the Langfuse ones")
    void genAiConventionsAreAlsoSet() {
        try (var generation = tracer.start("agent", ObservationType.GENERATION)) {
            generation.genAi("google_genai", "chat", "gemini-3.1-flash-lite");
        }

        // Langfuse gives its own namespace precedence, so these cost nothing there —
        // they are what a Tempo or spanmetrics query keys off, which knows no langfuse.*.
        assertThat(exported.getFinishedSpanItems().getFirst().getAttributes().asMap())
                .containsEntry(GenAiAttributes.SYSTEM, "google_genai")
                .containsEntry(GenAiAttributes.OPERATION_NAME, "chat")
                .containsEntry(GenAiAttributes.REQUEST_MODEL, "gemini-3.1-flash-lite");
    }
}
