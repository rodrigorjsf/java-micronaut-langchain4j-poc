package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.message.AiMessage;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One real turn, and the whole tree it produces.
 *
 * <p>The per-seam tests each prove that one listener writes the right attributes. None of
 * them can prove the thing this integration is actually for: that a single turn yields ONE
 * trace, with the turn at its head, every step underneath it, and the observation types that
 * make Langfuse draw an agent graph instead of a flat list.
 *
 * <p>It is also the only test that would fail if a seam were wired in the wrong place —
 * registered on the judge instead of the assistant, or attached to the model that
 * {@code addListener} returned a decorator around rather than to the decorator itself.
 */
class TurnTraceShapeTest {

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

    private Map<String, Long> typeCounts() {
        return exported.getFinishedSpanItems().stream()
                .map(span -> span.getAttributes().asMap().entrySet().stream()
                        .filter(e -> e.getKey().getKey().equals(LangfuseAttributes.OBSERVATION_TYPE.getKey()))
                        .map(e -> String.valueOf(e.getValue()))
                        .findFirst().orElse("<untyped>"))
                .collect(Collectors.groupingBy(type -> type, Collectors.counting()));
    }

    @Test
    @DisplayName("an answered turn is one trace, headed by the turn, with every seam under it")
    void aTurnIsOneTreeWithTheTurnAtItsHead() {
        models.model("judge").replyWith(verdict("IN_SCOPE", "DATA_REQUEST"));
        models.model("agent").replyWith("Bom dia! Como posso ajudar?");

        turns.handle(new ConversationId("conversa-shape"), "bom dia");

        var spans = exported.getFinishedSpanItems();
        assertThat(spans).isNotEmpty();

        // One trace. Anything with its own trace id is a seam whose context was lost.
        assertThat(spans).extracting(SpanData::getTraceId).containsOnly(spans.getFirst().getTraceId());

        var root = spans.stream().filter(s -> s.getParentSpanId().equals("0000000000000000"))
                .toList();
        assertThat(root).singleElement().satisfies(span ->
                assertThat(span.getName()).isEqualTo("chat-turn"));

        // Every other span descends from something in this trace, so the tree has no
        // detached branch.
        var ids = spans.stream().map(SpanData::getSpanId).collect(Collectors.toSet());
        assertThat(spans.stream()
                .filter(s -> !s.getParentSpanId().equals("0000000000000000"))
                .map(SpanData::getParentSpanId))
                .allMatch(ids::contains);
    }

    @Test
    @DisplayName("the turn produces the observation types that make Langfuse draw a graph")
    void theTurnProducesTypedObservations() {
        models.model("judge").replyWith(verdict("IN_SCOPE", "DATA_REQUEST"));
        models.model("agent").replyWith("O CEP é 01310-100.");

        // NOT a greeting. A bare "bom dia" is answered by the triage pre-filter without a
        // model call at all, so the judge generation this test counts would not exist —
        // which is the pre-filter working, not the tracing failing.
        turns.handle(new ConversationId("conversa-types"), "qual o cep da avenida paulista?");

        var counts = typeCounts();

        // agent: the turn itself, plus the AI-service invocations LangChain4j runs.
        // chain: triage. generation: every model call. guardrail: the input and output
        // chains. A trace holding only span/event/generation gets no agent graph at all.
        assertThat(counts).containsKeys("agent", "chain", "generation", "guardrail");
        assertThat(counts.get("generation")).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("a tool call appears as a tool observation inside the turn")
    void aToolCallIsATypedObservationInTheTurn() {
        models.model("judge").replyWith(verdict("IN_SCOPE", "DATA_REQUEST"));
        // First the model asks for a skill, then it answers. activate_skill is a real tool
        // in this pipeline, so this exercises the tool seam without a network call.
        models.model("agent")
                .reply(request -> AiMessage.from(List.of(
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                .id("1").name("activate_skill")
                                .arguments("{\"name\":\"brazil-civic-data\"}").build())))
                .replyWith("Pronto.");

        turns.handle(new ConversationId("conversa-tool"), "qual o cep da paulista?");

        assertThat(typeCounts()).containsKey("tool");
        var tool = exported.getFinishedSpanItems().stream()
                .filter(s -> "tool".equals(s.getAttributes().get(LangfuseAttributes.OBSERVATION_TYPE)))
                .findFirst().orElseThrow();
        assertThat(tool.getAttributes().get(GenAiAttributes.TOOL_NAME)).isEqualTo("activate_skill");
    }

    @Test
    @DisplayName("with content capture off, the user's text is in no attribute of any span")
    void contentCaptureIsHonouredAcrossEverySeam() {
        try (var quiet = ApplicationContext.run(Map.of(
                "agentic.test.stub-models", "true",
                "agentic.test.record-spans", "true",
                "agentic.observability.capture-content", "false",
                "agentic.guardrails.input.llm-classifier-enabled", "false",
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake"))) {

            var recorder = quiet.getBean(InMemorySpanExporter.class);
            var stub = (StubChatModelRegistry) quiet.getBean(ChatModelRegistry.class);
            stub.model("judge").replyWith(verdict("IN_SCOPE", "DATA_REQUEST"));
            stub.model("agent").replyWith("resposta");
            recorder.reset();

            quiet.getBean(ChatTurnService.class)
                    .handle(new ConversationId("conversa-quieta"), "meu CPF é 000.000.000-00");

            // The assertion is on the TEXT, not on the attribute names, and that is the
            // point. Several observations legitimately keep an input or an output with
            // capture off — a retriever's scores, an embedding's count and dimension, a
            // store search's threshold — because none of that is content, and a deployment
            // that turns capture off still has to be able to argue about its own threshold.
            // What must not survive anywhere is what the user typed.
            var everyValue = recorder.getFinishedSpanItems().stream()
                    .flatMap(span -> span.getAttributes().asMap().values().stream())
                    .map(String::valueOf)
                    .toList();

            assertThat(recorder.getFinishedSpanItems()).isNotEmpty();
            assertThat(everyValue).isNotEmpty();
            assertThat(everyValue).noneMatch(value -> value.contains("000.000.000-00"));
            assertThat(everyValue).noneMatch(value -> value.contains("meu CPF"));
            assertThat(everyValue).noneMatch(value -> value.contains("resposta"));
        }
    }
}
