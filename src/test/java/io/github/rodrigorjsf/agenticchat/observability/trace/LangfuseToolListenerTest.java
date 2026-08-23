package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.github.rodrigorjsf.agenticchat.testsupport.ScriptedChatModel;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool seam, exercised through a real {@code AiServices} whose scripted model asks
 * for a tool and then answers.
 */
class LangfuseToolListenerTest {

    interface Assistant {

        @UserMessage("{{message}}")
        String chat(@V("message") String message);
    }

    static class WeatherTools {

        @Tool("Current weather for a city")
        String weather(@P(name = "city", value = "City name") String city) {
            return "{\"tempC\":27}";
        }
    }

    private InMemorySpanExporter exported;
    private SdkTracerProvider tracerProvider;
    private AgentTracer tracer;
    private ObservationContentPolicy content;

    @BeforeEach
    void setUp() {
        exported = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exported))
                .build();
        tracer = new OtelAgentTracer(tracerProvider.get("test"), ObservationJson.compact());
        content = new ObservationContentPolicy(true, List.of());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    @DisplayName("a tool call is a tool observation under the invocation that made it")
    void aToolCallIsObservedUnderItsInvocation() {
        var agentListener = new LangfuseAiServiceListener(tracer, content, 8);
        var toolListener = new LangfuseToolListener(tracer, content);

        var model = new ScriptedChatModel()
                .reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("weather")
                        .arguments("{\"city\":\"Recife\"}")
                        .build()))
                .replyWith("Faz 27 graus no Recife.");

        var assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .tools(new WeatherTools())
                .registerListeners(agentListener.listeners())
                .registerListeners(toolListener.listeners())
                .build();

        assertThat(assistant.chat("que tempo faz no Recife?")).isEqualTo("Faz 27 graus no Recife.");

        var spans = exported.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName).containsExactly("weather", "Assistant.chat");

        var tool = spans.getFirst();
        var agent = spans.getLast();

        // The nesting is what makes the trace a graph rather than a list, and it comes
        // from the OpenTelemetry context alone — nothing is passed by hand between the
        // agent listener and this one.
        assertThat(tool.getParentSpanId()).isEqualTo(agent.getSpanId());
        assertThat(tool.getTraceId()).isEqualTo(agent.getTraceId());

        assertThat(tool.getAttributes().asMap())
                .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "tool")
                .containsEntry(GenAiAttributes.TOOL_NAME, "weather")
                .containsEntry(GenAiAttributes.TOOL_CALL_ID, "call-1")
                .containsEntry(GenAiAttributes.OPERATION_NAME, "execute_tool")
                // Both fields are stored as JSON strings, so a payload that is itself JSON
                // arrives escaped. That is deliberate: the model's arguments are not always
                // valid JSON, and a parse here would fail on exactly the calls worth
                // looking at.
                .containsEntry(LangfuseAttributes.OBSERVATION_INPUT, "\"{\\\"city\\\":\\\"Recife\\\"}\"")
                .containsEntry(LangfuseAttributes.OBSERVATION_OUTPUT, "\"{\\\"tempC\\\":27}\"");
    }

    @Test
    @DisplayName("the tool observation says in the trace that its duration is not the tool's")
    void theTimingIsDeclaredRatherThanImplied() {
        var toolListener = new LangfuseToolListener(tracer, content);

        toolListener.onEvent(toolExecuted(
                ToolExecutionRequest.builder().id("call-1").name("weather").arguments("{}").build(),
                List.of(TextContent.from("{\"tempC\":27}"))));

        // ToolExecutedEvent fires after the tool returned, so this span is a point, not an
        // interval. Without the note a reader finds a 0 ms tool observation beside a
        // six-second turn and concludes the upstream API is fast.
        var attributes = exported.getFinishedSpanItems().getFirst().getAttributes();
        assertThat(attributes.get(LangfuseAttributes.observationMetadata("timing")))
                .contains("not the tool's latency");
    }

    @Test
    @DisplayName("a result that is not one text part is described instead of throwing the observation away")
    void aNonTextResultIsDescribedNotThrown() {
        var toolListener = new LangfuseToolListener(tracer, content);

        // ToolExecutedEvent.resultText() throws IllegalStateException here, and LangChain4j
        // swallows a listener exception into a log line — so calling it would make the
        // observation disappear for every multimodal tool, with nothing red anywhere.
        toolListener.onEvent(toolExecuted(
                ToolExecutionRequest.builder().id("call-2").name("chart").arguments("{}").build(),
                List.of(TextContent.from("monthly SELIC"), ImageContent.from("https://example.test/chart.png"))));

        assertThat(exported.getFinishedSpanItems()).singleElement().satisfies(span ->
                assertThat(span.getAttributes().asMap())
                        .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "tool")
                        // The image is named, never inlined: a base64 payload in a span
                        // attribute is megabytes of trace for something nobody reads.
                        .containsEntry(LangfuseAttributes.OBSERVATION_OUTPUT,
                                "[\"monthly SELIC\",\"[IMAGE content]\"]"));
    }

    @Test
    @DisplayName("with content capture off, neither the arguments nor the result reach the span")
    void contentCaptureIsHonoured() {
        var toolListener = new LangfuseToolListener(tracer, new ObservationContentPolicy(false, List.of()));

        toolListener.onEvent(toolExecuted(
                ToolExecutionRequest.builder().id("call-3").name("cpf_lookup")
                        .arguments("{\"cpf\":\"000.000.000-00\"}").build(),
                List.of(TextContent.from("{\"name\":\"someone\"}"))));

        var span = exported.getFinishedSpanItems().getFirst();
        assertThat(span.getAttributes().asMap().keySet().stream().map(Object::toString))
                .doesNotContain(
                        LangfuseAttributes.OBSERVATION_INPUT.getKey(),
                        LangfuseAttributes.OBSERVATION_OUTPUT.getKey());
        // Which tool ran is not content, and it is what the agent graph is drawn from.
        assertThat(span.getName()).isEqualTo("cpf_lookup");
        assertThat(span.getAttributes().asMap()).containsEntry(GenAiAttributes.TOOL_NAME, "cpf_lookup");
    }

    /**
     * The cases a scripted model cannot reach — a multimodal tool result above all — are
     * fired at the listener directly. The event is this listener's input, so that is still
     * its seam, and the first test covers the path through a real {@code AiServices}.
     */
    private static ToolExecutedEvent toolExecuted(ToolExecutionRequest request, List<Content> results) {
        InvocationContext invocation = InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName(Assistant.class.getName())
                .methodName("chat")
                .chatMemoryId("default")
                .invocationParameters(new InvocationParameters())
                .timestampNow()
                .build();

        return ToolExecutedEvent.builder()
                .invocationContext(invocation)
                .request(request)
                .resultContents(results)
                .build();
    }
}
