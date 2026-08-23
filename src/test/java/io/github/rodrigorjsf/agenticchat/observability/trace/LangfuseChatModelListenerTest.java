package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.github.rodrigorjsf.agenticchat.observability.CostCalculator;
import io.github.rodrigorjsf.agenticchat.observability.ModelPrice;
import io.github.rodrigorjsf.agenticchat.testsupport.ScriptedChatModel;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model call, observed as a Langfuse {@code generation}.
 *
 * <p>Driven through {@link ScriptedChatModel} — which notifies its listeners exactly
 * as a provider model does — and asserted against exported spans, never against the
 * listener's own fields. Langfuse reads the exported span and nothing else, so a test
 * that inspected the listener would pass while the integration was blind.
 *
 * <p>Four cases are driven by constructing the listener contexts directly instead. That
 * is not a shortcut around the double: the double always reports usage, never fails, and
 * reports no {@code ModelProvider}, so a response with no usage, an error, the
 * provider-derived {@code gen_ai.system} and a response whose model name differs from
 * the request's cannot be expressed through it. Those tests call the same three methods,
 * in the same order, with the same shared attribute map that LangChain4j's own
 * {@code ChatModelListenerUtils} uses.
 */
class LangfuseChatModelListenerTest {

    private static final String GEMINI = "gemini-2.5-flash-lite";

    private InMemorySpanExporter exported;
    private SdkTracerProvider tracerProvider;
    private AgentTracer tracer;
    private LangfuseChatModelListener listener;
    private ScriptedChatModel model;

    @BeforeEach
    void setUp() {
        exported = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exported))
                .build();
        tracer = new OtelAgentTracer(tracerProvider.get("test"), ObservationJson.compact());
        listener = listenerCapturing(true);
        model = new ScriptedChatModel().withListeners(List.of(listener));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    private LangfuseChatModelListener listenerCapturing(boolean captureContent) {
        return new LangfuseChatModelListener(
                "agent", tracer, prices(), new ObservationContentPolicy(captureContent, List.of()));
    }

    /**
     * The prices {@code application.yml} configures for the two models named here, under
     * the keys a Micronaut property segment forces: a dot cannot appear in one, so
     * {@code gemini-2.5-flash-lite} is written {@code gemini-2-5-flash-lite} and
     * {@link CostCalculator} normalises the requested name back to it. Pricing the
     * request under its real, dotted name is part of what these tests assert.
     */
    private static CostCalculator prices() {
        return new CostCalculator(List.of(
                new ModelPrice("gemini-2-5-flash-lite",
                        new BigDecimal("0.10"), new BigDecimal("0.40"), new BigDecimal("0.01")),
                new ModelPrice("gpt-4o-mini",
                        new BigDecimal("0.15"), new BigDecimal("0.60"), new BigDecimal("0.075"))));
    }

    @Test
    @DisplayName("a model call is one generation observation named after the role that made it")
    void aModelCallIsOneGeneration() {
        model.replyWith("Bom dia!");

        model.chat(request(UserMessage.from("bom dia")));

        var span = generation();
        assertThat(span.getName()).isEqualTo("agent");
        assertThat(span.getAttributes().asMap())
                // Langfuse draws usage and cost on this type and on no other, and the
                // role is what joins a span here to the role-tagged Prometheus counters.
                .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "generation")
                .containsEntry(LangfuseAttributes.observationMetadata("role"), "agent")
                .containsEntry(LangfuseAttributes.observationMetadata("finish_reason"), "STOP");
    }

    @Test
    @DisplayName("the requested model and its sampling parameters travel with the generation")
    void theModelAndItsParametersAreWritten() {
        model.replyWith("Bom dia!");

        model.chat(ChatRequest.builder()
                .messages(UserMessage.from("bom dia"))
                .modelName(GEMINI)
                .temperature(0.3)
                .maxOutputTokens(2048)
                .topP(0.9)
                .build());

        var attributes = generation().getAttributes();
        assertThat(attributes.get(LangfuseAttributes.MODEL_NAME)).isEqualTo(GEMINI);
        assertThat(attributes.get(LangfuseAttributes.MODEL_PARAMETERS)).contains(
                "\"temperature\":0.3", "\"max_output_tokens\":2048", "\"top_p\":0.9");
    }

    @Test
    @DisplayName("the input is the whole conversation, system prompt included")
    void theInputIsTheConversation() {
        model.replyWith("Bom dia!");

        model.chat(request(
                SystemMessage.from("Answer only what the skills cover."),
                UserMessage.from("bom dia")));

        // Exact, because this map is built here and handed straight to the serializer:
        // its order is the order Langfuse renders the conversation in.
        assertThat(generation().getAttributes().get(LangfuseAttributes.OBSERVATION_INPUT))
                .isEqualTo("[{\"role\":\"system\",\"content\":\"Answer only what the skills cover.\"},"
                        + "{\"role\":\"user\",\"content\":\"bom dia\"}]");
    }

    @Test
    @DisplayName("token usage reaches the span as the exclusive buckets Langfuse expects")
    void usageIsWritten() {
        model.replyWith("Bom dia!");

        model.chat(request(UserMessage.from("bom dia")));

        // Per entry rather than as one string: TokenUsageDetails.buckets() is a
        // Map.copyOf, whose iteration order comes from a per-JVM salt. Asserting the
        // whole JSON would be intermittently red for no reason.
        assertThat(generation().getAttributes().get(LangfuseAttributes.USAGE_DETAILS))
                .contains("\"input\":100", "\"output\":20", "\"total\":120");
    }

    @Test
    @DisplayName("cost is ingested per bucket rather than left for Langfuse to infer")
    void costIsIngested() {
        model.replyWith("Bom dia!");

        model.chat(ChatRequest.builder()
                .messages(UserMessage.from("bom dia"))
                .modelName(GEMINI)
                .build());

        // 100 input at $0.10/M and 20 output at $0.40/M. Ingesting the number stops
        // Langfuse inferring a different one from its own model definitions, which is
        // the only way the trace and the Prometheus counter can agree.
        assertThat(generation().getAttributes().get(LangfuseAttributes.COST_DETAILS))
                .contains("\"input\":0.00001", "\"output\":0.000008", "\"total\":0.000018");
    }

    @Test
    @DisplayName("a turn where the model asked for a tool has the calls as its output")
    void aToolCallingResponseIsWrittenAsOutput() {
        model.reply(asked -> AiMessage.from(ToolExecutionRequest.builder()
                .id("call_1")
                .name("get_weather")
                .arguments("{\"city\":\"Recife\"}")
                .build()));

        model.chat(request(UserMessage.from("vai chover em Recife?")));

        // Without this the generation's output is empty and the trace shows the model
        // saying nothing, which is the opposite of what it did.
        assertThat(generation().getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .contains("\"tool_calls\"", "\"id\":\"call_1\"", "\"name\":\"get_weather\"", "Recife");
    }

    @Test
    @DisplayName("with content capture off the generation carries shape but no text")
    void contentCaptureOffWritesNoInputOrOutput() {
        var quiet = new ScriptedChatModel().withListeners(List.of(listenerCapturing(false)));
        quiet.replyWith("Bom dia!");

        quiet.chat(ChatRequest.builder()
                .messages(UserMessage.from("bom dia"))
                .modelName(GEMINI)
                .build());

        var attributes = generation().getAttributes();
        assertThat(attributes.get(LangfuseAttributes.OBSERVATION_INPUT)).isNull();
        assertThat(attributes.get(LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
        // Usage, cost and the response-side facts are not content: switching capture
        // off must not also switch off the accounting, or a data-protection setting
        // silently becomes a billing one and the trace stops saying why a turn ended.
        assertThat(attributes.get(LangfuseAttributes.USAGE_DETAILS)).isNotNull();
        assertThat(attributes.get(LangfuseAttributes.COST_DETAILS)).isNotNull();
        assertThat(attributes.asMap())
                .containsEntry(LangfuseAttributes.observationMetadata("role"), "agent")
                .containsEntry(LangfuseAttributes.observationMetadata("finish_reason"), "STOP");
    }

    @Test
    @DisplayName("a failed model call is closed at ERROR level rather than left open")
    void aFailedCallIsRecordedAndClosed() {
        var request = ChatRequest.builder()
                .messages(UserMessage.from("bom dia"))
                .modelName(GEMINI)
                .build();
        var attributes = begin(request, ModelProvider.GOOGLE_AI_GEMINI);

        listener.onError(new ChatModelErrorContext(
                new RuntimeException("429 RESOURCE_EXHAUSTED"), request, ModelProvider.GOOGLE_AI_GEMINI, attributes));

        // Exported at all is half the assertion: a span that is never ended is never
        // sent, so a failing provider would simply be missing from the trace.
        assertThat(generation().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "generation")
                .containsEntry(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR")
                .containsEntry(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, "429 RESOURCE_EXHAUSTED");
    }

    @Test
    @DisplayName("a response reporting no usage at all is still one closed generation")
    void aResponseWithoutUsageIsStillExported() {
        var request = ChatRequest.builder()
                .messages(UserMessage.from("bom dia"))
                .modelName(GEMINI)
                .build();
        var attributes = begin(request, ModelProvider.GOOGLE_AI_GEMINI);

        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("Bom dia!")).build(),
                request, ModelProvider.GOOGLE_AI_GEMINI, attributes));

        var attributesOfSpan = generation().getAttributes();
        // Absent, not zero: a call whose usage the provider did not report is a
        // different claim from a call that used no tokens, and Langfuse shows the
        // difference where a confident zero would hide it.
        assertThat(attributesOfSpan.get(LangfuseAttributes.USAGE_DETAILS)).isNull();
        assertThat(attributesOfSpan.get(LangfuseAttributes.COST_DETAILS)).isNull();
        assertThat(attributesOfSpan.get(LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("\"Bom dia!\"");
    }

    @Test
    @DisplayName("the GenAI conventions name the provider SDK that made the call")
    void genAiConventionsNameTheProvider() {
        var request = ChatRequest.builder()
                .messages(UserMessage.from("bom dia"))
                .modelName(GEMINI)
                .build();
        var attributes = begin(request, ModelProvider.GOOGLE_AI_GEMINI);

        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("Bom dia!")).build(),
                request, ModelProvider.GOOGLE_AI_GEMINI, attributes));

        // These buy nothing in Langfuse, which gives its own namespace precedence.
        // They are what a Tempo query or the spanmetrics connector groups by.
        assertThat(generation().getAttributes().asMap())
                .containsEntry(GenAiAttributes.SYSTEM, "google_ai_gemini")
                .containsEntry(GenAiAttributes.OPERATION_NAME, "chat")
                .containsEntry(GenAiAttributes.REQUEST_MODEL, GEMINI);
    }

    @Test
    @DisplayName("cost is priced by the model that was requested, not the build that answered")
    void costIsPricedByTheRequestedModel() {
        var request = ChatRequest.builder()
                .messages(UserMessage.from("bom dia"))
                .modelName("gpt-4o-mini")
                .build();
        var attributes = begin(request, ModelProvider.OPEN_AI);

        // OpenAI answers with the dated build it actually served. That name is not a key
        // in agentic.llm.pricing, so pricing by it yields no cost at all — silently, and
        // only on this provider.
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("Bom dia!"))
                        .modelName("gpt-4o-mini-2024-07-18")
                        .tokenUsage(new TokenUsage(100, 20))
                        .build(),
                request, ModelProvider.OPEN_AI, attributes));

        var attributesOfSpan = generation().getAttributes();
        // 100 input at $0.15/M and 20 output at $0.60/M.
        assertThat(attributesOfSpan.get(LangfuseAttributes.COST_DETAILS)).contains("\"total\":0.000027");
        // The build that served the call is not lost, only kept somewhere it cannot be
        // mistaken for a pricing key.
        assertThat(attributesOfSpan.asMap())
                .containsEntry(LangfuseAttributes.observationMetadata("response_model"), "gpt-4o-mini-2024-07-18");
    }

    private static ChatRequest request(ChatMessage... messages) {
        return ChatRequest.builder().messages(messages).build();
    }

    /**
     * Opens the observation the way {@code ChatModelListenerUtils} does, and hands back
     * the attribute map it stashed into — the same map the ending callback must be given
     * for the span to be closed rather than leaked.
     */
    private Map<Object, Object> begin(ChatRequest request, ModelProvider provider) {
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, provider, attributes));
        return attributes;
    }

    private SpanData generation() {
        assertThat(exported.getFinishedSpanItems()).hasSize(1);
        return exported.getFinishedSpanItems().getFirst();
    }
}
