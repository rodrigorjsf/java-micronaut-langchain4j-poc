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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The root observation of a turn.
 *
 * <p>Langfuse v4 has no separate trace entity: "a trace is a group of observations correlated by
 * trace ID", and the overall request and response belong on the ROOT observation. That is why the
 * HTTP server span is excluded for this route — a root named {@code POST /api/chat} with no input
 * and no output would push the only content one level down, where a trace-level evaluator does not
 * look.
 */
class TurnObservationTest {

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

    private static String verdict(String decision, String intent) {
        return """
                {"decision":"%s","confidence":0.95,"intent":"%s","language":"pt-BR",
                 "skillHint":"","riskFlags":[]}""".formatted(decision, intent);
    }

    private SpanData root() {
        return exported.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("chat-turn"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no chat-turn observation was exported"));
    }

    @Test
    @DisplayName("an answered turn is one agent observation carrying the message and the reply")
    void anAnsweredTurnIsTheRootObservation() {
        models.model("judge").replyWith(verdict("IN_SCOPE", "GENERAL_QUESTION"));
        models.model("agent").replyWith("Bom dia! Como posso ajudar?");

        turns.handle(new ConversationId("conversa-1"), "bom dia");

        assertThat(root().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "agent")
                .containsEntry(LangfuseAttributes.OBSERVATION_INPUT, "\"bom dia\"")
                .containsEntry(LangfuseAttributes.OBSERVATION_OUTPUT, "\"Bom dia! Como posso ajudar?\"")
                .containsEntry(LangfuseAttributes.observationMetadata("outcome"), "ANSWERED");
    }

    @Test
    @DisplayName("the conversation is the Langfuse session, on every observation of the turn")
    void theConversationIsTheSession() {
        models.model("judge").replyWith(verdict("IN_SCOPE", "GENERAL_QUESTION"));
        models.model("agent").replyWith("ok");

        turns.handle(new ConversationId("conversa-7"), "bom dia");

        // Every span, not only the root: Langfuse aggregates over observations.
        assertThat(exported.getFinishedSpanItems()).isNotEmpty().allSatisfy(span ->
                assertThat(span.getAttributes().asMap())
                        .containsEntry(LangfuseAttributes.SESSION_ID, "conversa-7"));
    }

    @Test
    @DisplayName("a refused turn is still a complete observation, with the refusal as its output")
    void aRefusedTurnIsAlsoObserved() {
        models.model("judge").replyWith(verdict("OUT_OF_SCOPE", "OFF_TOPIC"));

        var turn = turns.handle(new ConversationId("conversa-2"), "escreva um poema sobre o mar");

        assertThat(root().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.observationMetadata("outcome"), "REFUSED")
                .containsEntry(LangfuseAttributes.OBSERVATION_OUTPUT, "\"" + turn.reply() + "\"");
    }

    @Test
    @DisplayName("the triage decision is on the root observation, where a filter can reach it")
    void theTriageVerdictIsRecorded() {
        models.model("judge").replyWith(verdict("IN_SCOPE", "DATA_REQUEST"));
        models.model("agent").replyWith("Vai chover.");

        turns.handle(new ConversationId("conversa-3"), "vai chover amanhã?");

        assertThat(root().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.observationMetadata("intent"), "DATA_REQUEST");
    }

    @Test
    @DisplayName("everything the turn does hangs under the root, so the trace is one tree")
    void everyObservationOfTheTurnIsInTheSameTrace() {
        models.model("judge").replyWith(verdict("IN_SCOPE", "GENERAL_QUESTION"));
        models.model("agent").replyWith("ok");

        turns.handle(new ConversationId("conversa-4"), "bom dia");

        var spans = exported.getFinishedSpanItems();
        var rootTraceId = root().getTraceId();
        assertThat(spans).allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(rootTraceId));
        assertThat(root().getParentSpanId()).isEqualTo("0000000000000000");
    }
}
