package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An experiment run, asserted through exported spans.
 *
 * <p>Langfuse synthesises an experiment out of ordinary traces: there is no experiment
 * entity on the wire, only {@code langfuse.experiment.*} attributes that every span in
 * every item trace has to carry. That is the same shape as the turn attributes, and it is
 * carried the same way — a private {@link io.opentelemetry.context.ContextKey} read by
 * {@link TurnAttributesSpanProcessor}, rather than the OpenTelemetry Baggage the Langfuse
 * documentation suggests. Baggage is injected into outbound request headers, and this
 * application's tools call fifty third-party APIs.
 *
 * <p>Contract read 2026-08-23 from
 * {@code https://langfuse.com/integrations/native/opentelemetry/experiments}.
 */
class ExperimentObservationTest {

    private InMemorySpanExporter exported;
    private SdkTracerProvider tracerProvider;
    private AgentTracer tracer;

    @BeforeEach
    void setUp() {
        exported = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(new TurnAttributesSpanProcessor(
                        new DeploymentIdentity("experiment", "0.1", "")))
                .addSpanProcessor(SimpleSpanProcessor.create(exported))
                .build();
        tracer = new OtelAgentTracer(tracerProvider.get("test"), ObservationJson.compact());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    @DisplayName("the experiment identity reaches every span in the item trace, not only the root")
    void experimentIdentityReachesEverySpan() {
        var experiment = ExperimentAttributes.builder()
                .id("exp-7")
                .name("triage-golden-set")
                .datasetId("ds-triage")
                .description("the shipped golden set")
                .metadata(Map.of("prompt_version", "v4"))
                .build();

        try (var ignored = ExperimentContext.open(experiment);
             var root = tracer.start("experiment-item", ObservationType.AGENT)) {
            try (var child = tracer.start("triage", ObservationType.CHAIN)) {
                assertThat(child).isNotNull();
            }
        }

        // Langfuse v4 queries observations, not traces. An experiment id that lived only
        // on the root would make every child unattributable, and the failure is silent —
        // the run appears to contain one observation per item.
        assertThat(exported.getFinishedSpanItems()).hasSize(2).allSatisfy(span ->
                assertThat(span.getAttributes().asMap())
                        .containsEntry(LangfuseAttributes.EXPERIMENT_ID, "exp-7")
                        .containsEntry(LangfuseAttributes.EXPERIMENT_NAME, "triage-golden-set")
                        .containsEntry(LangfuseAttributes.EXPERIMENT_DATASET_ID, "ds-triage")
                        .containsEntry(LangfuseAttributes.EXPERIMENT_DESCRIPTION, "the shipped golden set")
                        .containsEntry(LangfuseAttributes.experimentMetadata("prompt_version"), "v4"));
    }

    @Test
    @DisplayName("the item root names ITSELF as the item's root observation")
    void theItemRootNamesItself() {
        var experiment = ExperimentAttributes.builder().id("exp-7").name("triage-golden-set").build();

        String rootSpanId;
        try (var ignored = ExperimentContext.open(experiment);
             var root = tracer.start("experiment-item", ObservationType.AGENT)) {
            root.experimentItem("item-3", "IN_SCOPE");
            rootSpanId = root.ref().observationId();
        }

        // root_observation_id is not a value a caller can supply correctly: it is the span
        // id of a span that does not exist until it is started. Deriving it inside the
        // observation is the only spelling that cannot drift.
        assertThat(exported.getFinishedSpanItems()).singleElement().satisfies(span -> {
            assertThat(span.getAttributes().asMap())
                    .containsEntry(LangfuseAttributes.EXPERIMENT_ITEM_ID, "item-3")
                    .containsEntry(LangfuseAttributes.EXPERIMENT_ITEM_ROOT_OBSERVATION_ID, rootSpanId)
                    .containsEntry(LangfuseAttributes.EXPERIMENT_ITEM_EXPECTED_OUTPUT, "\"IN_SCOPE\"");
            assertThat(span.getSpanId()).isEqualTo(rootSpanId);
        });
    }

    @Test
    @DisplayName("the item identity propagates to the children started after it is declared")
    void theItemIdentityPropagatesToChildren() {
        var experiment = ExperimentAttributes.builder().id("exp-7").name("triage-golden-set").build();

        try (var ignored = ExperimentContext.open(experiment);
             var root = tracer.start("experiment-item", ObservationType.AGENT)) {
            root.experimentItem("item-3", null);
            try (var itemScope = ExperimentContext.openItem(root.ref().observationId(), "item-3", null);
                 var child = tracer.start("triage", ObservationType.CHAIN)) {
                assertThat(child).isNotNull();
            }
        }

        var child = exported.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("triage"))
                .findFirst()
                .orElseThrow();
        assertThat(child.getAttributes().asMap())
                .containsEntry(LangfuseAttributes.EXPERIMENT_ITEM_ID, "item-3")
                .containsKey(LangfuseAttributes.EXPERIMENT_ITEM_ROOT_OBSERVATION_ID);
    }

    @Test
    @DisplayName("outside an experiment no experiment attribute is written at all")
    void outsideAnExperimentNothingIsWritten() {
        try (var turn = tracer.start("turn", ObservationType.AGENT)) {
            assertThat(turn).isNotNull();
        }

        // An empty string would be a claim that this span belongs to an experiment with a
        // blank id, which Langfuse would group. Absent is the only correct spelling.
        assertThat(exported.getFinishedSpanItems()).singleElement().satisfies(span ->
                assertThat(span.getAttributes().asMap().keySet())
                        .noneMatch(key -> key.getKey().startsWith("langfuse.experiment.")));
    }
}
