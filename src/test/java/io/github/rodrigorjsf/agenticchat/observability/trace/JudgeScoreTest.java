package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.github.rodrigorjsf.agenticchat.conversation.ChatTurnService;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.memory.ConversationId;
import io.github.rodrigorjsf.agenticchat.testsupport.RecordingScoreWriter;
import io.github.rodrigorjsf.agenticchat.testsupport.StubChatModelRegistry;
import io.micronaut.context.ApplicationContext;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The judge's verdict, recorded as a Langfuse score.
 *
 * <p>A score rather than a span attribute, and that is not a stylistic choice. Langfuse
 * aggregates scores across traces — an average confidence, a distribution, a filter for
 * every turn the judge was unsure about — and it does none of that for an arbitrary
 * attribute. It is also the mechanism a human annotation and an offline evaluator write
 * into, so the judge's own opinion lands in the same column its later corrections do.
 *
 * <p>Scores do not travel over OTLP. Langfuse's migration guide is explicit: a score event
 * goes to the Scores API, "not an OTLP trace span". That is why the writer exists at all.
 */
class JudgeScoreTest {

    private ApplicationContext ctx;
    private StubChatModelRegistry models;
    private RecordingScoreWriter scores;
    private ChatTurnService turns;

    @BeforeEach
    void setUp() {
        Map<String, Object> config = new HashMap<>();
        config.put("agentic.test.stub-models", "true");
        config.put("agentic.test.record-spans", "true");
        config.put("agentic.test.record-scores", "true");
        config.put("agentic.llm.credentials.google-api-key", "fake");
        config.put("agentic.llm.credentials.openai-api-key", "fake");
        config.put("agentic.guardrails.input.llm-classifier-enabled", "false");

        ctx = ApplicationContext.run(config);
        models = (StubChatModelRegistry) ctx.getBean(ChatModelRegistry.class);
        scores = ctx.getBean(RecordingScoreWriter.class);
        turns = ctx.getBean(ChatTurnService.class);
        ctx.getBean(InMemorySpanExporter.class).reset();
        scores.reset();
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private static String verdict(String decision, String intent, double confidence) {
        return """
                {"decision":"%s","confidence":%s,"intent":"%s","language":"pt-BR",
                 "skillHint":"","riskFlags":[]}""".formatted(decision, confidence, intent);
    }

    @Test
    @DisplayName("the judge's confidence is a numeric score on the observation it judged")
    void confidenceIsANumericScore() {
        models.model("judge").replyWith(verdict("IN_SCOPE", "DATA_REQUEST", 0.93));
        models.model("agent").replyWith("O CEP é 01310-100.");

        turns.handle(new ConversationId("conversa-score"), "qual o cep da avenida paulista?");

        assertThat(scores.scoresNamed("triage_confidence")).singleElement().satisfies(score -> {
            assertThat(score.numericValue()).isEqualTo(0.93);
            assertThat(score.dataType()).isEqualTo(Score.DataType.NUMERIC);
        });

        // Attached to an observation, not floating on the trace: an observation-level
        // evaluator in Langfuse matches an observation and does not read its siblings.
        assertThat(scores.recorded()).allSatisfy(recorded -> {
            assertThat(recorded.target()).isNotNull();
            assertThat(recorded.target().traceId()).hasSize(32);
            assertThat(recorded.target().observationId()).hasSize(16);
        });
    }

    @Test
    @DisplayName("the decision is a categorical score, so it can be grouped rather than averaged")
    void theDecisionIsCategorical() {
        models.model("judge").replyWith(verdict("OUT_OF_SCOPE", "OFF_TOPIC", 0.88));

        turns.handle(new ConversationId("conversa-recusa"), "escreva um poema sobre o mar");

        assertThat(scores.scoresNamed("triage_decision")).singleElement().satisfies(score -> {
            assertThat(score.stringValue()).isEqualTo("OUT_OF_SCOPE");
            assertThat(score.dataType()).isEqualTo(Score.DataType.CATEGORICAL);
        });
    }

    @Test
    @DisplayName("a turn the pre-filter answered records no judge score, because no judge ran")
    void thePreFilterPathScoresNothing() {
        // "bom dia" never reaches the model. A confidence recorded here would be an
        // invented number attributed to a judge that was not asked.
        turns.handle(new ConversationId("conversa-prefilter"), "bom dia");

        assertThat(scores.scoresNamed("triage_confidence")).isEmpty();
    }
}
