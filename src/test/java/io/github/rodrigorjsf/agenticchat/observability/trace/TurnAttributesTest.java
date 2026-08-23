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
 * Trace-level attributes reaching every observation, not only the root.
 *
 * <p>This is not a nicety. Langfuse v4 queries observations directly, so an attribute
 * that exists only on the root span is unavailable when filtering or aggregating its
 * children: "if you want to reliably filter or aggregate by these attributes, they need
 * to be present on each span in the trace". A user id on the root alone means the user
 * filter silently returns one observation out of twelve.
 */
class TurnAttributesTest {

    private InMemorySpanExporter exported;
    private SdkTracerProvider tracerProvider;
    private AgentTracer tracer;

    @BeforeEach
    void setUp() {
        exported = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(new TurnAttributesSpanProcessor(
                        new DeploymentIdentity("local", "0.1", "test-release")))
                .addSpanProcessor(SimpleSpanProcessor.create(exported))
                .build();
        tracer = new OtelAgentTracer(tracerProvider.get("test"), ObservationJson.compact());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    @DisplayName("every observation in the turn carries the turn's user and session")
    void turnAttributesReachEveryObservation() {
        var turn = TurnAttributes.builder()
                .traceName("chat-turn")
                .userId("user-42")
                .sessionId("conversation-7")
                .build();

        try (var ignored = TurnContext.open(turn);
             var root = tracer.start("chat-turn", ObservationType.AGENT)) {
            try (var child = tracer.start("weather", ObservationType.TOOL)) {
                assertThat(child).isNotNull();
            }
        }

        assertThat(exported.getFinishedSpanItems()).hasSize(2).allSatisfy(span ->
                assertThat(span.getAttributes().asMap())
                        .containsEntry(LangfuseAttributes.USER_ID, "user-42")
                        .containsEntry(LangfuseAttributes.SESSION_ID, "conversation-7")
                        .containsEntry(LangfuseAttributes.TRACE_NAME, "chat-turn"));
    }

    @Test
    @DisplayName("the deployment identity is on every observation too")
    void deploymentIdentityIsAlwaysPresent() {
        try (var ignored = TurnContext.open(TurnAttributes.builder().traceName("t").build());
             var root = tracer.start("t", ObservationType.AGENT)) {
            assertThat(root).isNotNull();
        }

        assertThat(exported.getFinishedSpanItems().getFirst().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.ENVIRONMENT, "local")
                .containsEntry(LangfuseAttributes.VERSION, "0.1")
                .containsEntry(LangfuseAttributes.RELEASE, "test-release");
    }

    @Test
    @DisplayName("tags and turn metadata are written in the filterable form")
    void tagsAndMetadataAreFilterable() {
        var turn = TurnAttributes.builder()
                .traceName("chat-turn")
                .tags(List.of("triage", "pt-BR"))
                .metadata(Map.of("intent", "WEATHER"))
                .build();

        try (var ignored = TurnContext.open(turn);
             var root = tracer.start("chat-turn", ObservationType.AGENT)) {
            assertThat(root).isNotNull();
        }

        var attributes = exported.getFinishedSpanItems().getFirst().getAttributes();
        assertThat(attributes.get(LangfuseAttributes.TRACE_TAGS)).containsExactly("triage", "pt-BR");
        assertThat(attributes.get(LangfuseAttributes.traceMetadata("intent"))).isEqualTo("WEATHER");
    }

    @Test
    @DisplayName("outside a turn only the deployment identity is written")
    void noTurnMeansNoTurnAttributes() {
        try (var root = tracer.start("startup", ObservationType.SPAN)) {
            assertThat(root).isNotNull();
        }

        var attributes = exported.getFinishedSpanItems().getFirst().getAttributes().asMap();
        assertThat(attributes).containsEntry(LangfuseAttributes.ENVIRONMENT, "local");
        assertThat(attributes.keySet().stream().map(Object::toString))
                .doesNotContain(LangfuseAttributes.USER_ID.getKey());
    }

    @Test
    @DisplayName("the turn context does not leak into outbound request headers")
    void turnAttributesAreNotCarriedInBaggage() {
        var turn = TurnAttributes.builder().traceName("t").userId("user-42").build();

        try (var ignored = TurnContext.open(turn)) {
            // Langfuse recommends OpenTelemetry Baggage for this, and its own docs warn
            // that "OpenTelemetry baggage is propagated across service boundaries and to
            // third-party APIs". Every tool in this application calls a third-party API
            // with model-influenced arguments, so the turn's identity travels in a
            // private context key instead and cannot be injected into any header.
            assertThat(io.opentelemetry.api.baggage.Baggage.current().isEmpty()).isTrue();
            assertThat(TurnContext.current()).isPresent();
        }

        assertThat(TurnContext.current()).isEmpty();
    }
}
