package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.github.rodrigorjsf.agenticchat.conversation.ChatTurnService;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.memory.ConversationId;
import io.github.rodrigorjsf.agenticchat.testsupport.StubChatModelRegistry;
import io.micronaut.context.ApplicationContext;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the triage step leaves behind in a trace.
 *
 * <p>Before this existed, a reader opening a turn in Langfuse saw the assistant's work and,
 * from triage, two scores — a confidence and a decision, and nothing that said what text
 * had been judged or what the judge answered. Scores are aggregates: they are the right
 * shape for "the average confidence this week" and the wrong shape for "what did the judge
 * see on THAT turn". The verdict was reachable only by inferring it from the number.
 *
 * <p>The gap is widest on the path that costs nothing, which is also the common one.
 * {@code CachedTriageJudge} answers repeated text from a Caffeine cache, so no model call
 * happens and no {@code GENERATION} observation is produced — while
 * {@link io.github.rodrigorjsf.agenticchat.triage.TriageService} still records the scores.
 * A trace of a cached turn therefore showed a judge's opinion with no judge anywhere in
 * it. The observation asserted here is what makes the cached path visible, and its
 * {@code cached} metadata is what tells the two apart.
 */
class TriageObservationTest {

    private ApplicationContext ctx;
    private InMemorySpanExporter exported;
    private StubChatModelRegistry models;
    private ChatTurnService turns;

    @BeforeEach
    void setUp() {
        Map<String, Object> config = new HashMap<>();
        config.put("agentic.test.stub-models", "true");
        config.put("agentic.test.record-spans", "true");
        config.put("agentic.llm.credentials.google-api-key", "fake");
        config.put("agentic.llm.credentials.openai-api-key", "fake");
        config.put("agentic.guardrails.input.llm-classifier-enabled", "false");

        ctx = ApplicationContext.run(config);
        exported = ctx.getBean(InMemorySpanExporter.class);
        models = (StubChatModelRegistry) ctx.getBean(ChatModelRegistry.class);
        turns = ctx.getBean(ChatTurnService.class);
        exported.reset();
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

    private SpanData judgeSpan() {
        List<SpanData> judged = exported.getFinishedSpanItems().stream()
                .filter(span -> "triage-judge".equals(span.getName()))
                .toList();
        assertThat(judged).as("the triage-judge observation").hasSize(1);
        return judged.getFirst();
    }

    @Test
    @DisplayName("the judge's input and its verdict are on an observation, not only in a score")
    void theJudgeIsAnObservationWithInputAndOutput() {
        models.model("judge").replyWith(verdict("IN_SCOPE", "DATA_REQUEST", 0.93));
        models.model("agent").replyWith("O CEP é 01310-100.");

        turns.handle(new ConversationId("triagem-observada"), "qual o cep da avenida paulista?");

        var span = judgeSpan();
        assertThat(span.getAttributes().get(LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("span");
        assertThat(span.getAttributes().get(LangfuseAttributes.OBSERVATION_INPUT))
                .contains("qual o cep da avenida paulista?");
        // The verdict itself, not a rendering of it: a reader has to be able to see the
        // decision, the intent and the confidence that produced the two scores.
        assertThat(span.getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .contains("IN_SCOPE")
                .contains("DATA_REQUEST");
    }

    @Test
    @DisplayName("a cached verdict still produces the observation, and it stands alone")
    void theCachedPathIsVisible() {
        models.model("judge").replyWith(verdict("IN_SCOPE", "DATA_REQUEST", 0.93));
        models.model("agent").replyWith("O CEP é 01310-100.");
        turns.handle(new ConversationId("triagem-primeira"), "qual o cep da avenida paulista?");

        // The same text, so CachedTriageJudge answers from the cache and the model is never
        // asked again. This is the path that used to leave nothing but two scores.
        exported.reset();
        turns.handle(new ConversationId("triagem-repetida"), "qual o cep da avenida paulista?");

        var span = judgeSpan();
        assertThat(span.getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT)).contains("IN_SCOPE");

        // No generation underneath it, and that ABSENCE is the honest rendering of a
        // remembered verdict. Typing this observation GENERATION instead would have put a
        // model call with no tokens and no model name in the trace on every cached turn.
        assertThat(exported.getFinishedSpanItems())
                .filteredOn(other -> span.getSpanId().equals(other.getParentSpanId()))
                .as("children of the judge observation on a cached turn")
                .isEmpty();
    }

    @Test
    @DisplayName("a turn the pre-filter answered has no judge observation, because no judge ran")
    void thePreFilterPathIsNotAJudgeObservation() {
        models.model("agent").replyWith("Bom dia!");

        turns.handle(new ConversationId("triagem-prefilter"), "bom dia");

        assertThat(exported.getFinishedSpanItems())
                .as("a pre-filtered turn never asks the judge")
                .noneMatch(span -> "triage-judge".equals(span.getName()));
    }
}
