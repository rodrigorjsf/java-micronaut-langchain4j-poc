package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.github.rodrigorjsf.agenticchat.testsupport.ScriptedChatModel;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The AI-service seam, exercised through a real {@code AiServices} and asserted on the
 * spans an OTLP exporter would send.
 *
 * <p>Real rather than a hand-fired event, because the thing worth pinning is that
 * LangChain4j 1.18.1 fires the pair this listener depends on — one started event and
 * exactly one of completed or error — for both a normal and a failing invocation. A test
 * that built the events itself would pass while that contract changed underneath it.
 */
class LangfuseAiServiceListenerTest {

    interface Assistant {

        @UserMessage("{{message}}")
        String chat(@V("message") String message);
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

        // The never-completed test below strands an OpenTelemetry scope on this thread by
        // construction — that is the behaviour it pins. JUnit reuses the thread between
        // tests, so without this reset the next test's span would be parented into a dead
        // trace and fail for a reason that has nothing to do with what it asserts.
        // Deliberately not closed: closing this scope would restore the stranded context.
        Context.root().makeCurrent();
    }

    @Test
    @DisplayName("an AI-service invocation is one agent observation, named for the service and the method")
    void anInvocationIsOneAgentObservation() {
        var listener = new LangfuseAiServiceListener(tracer, content, 8);
        var assistant = assistant(listener, new ScriptedChatModel().replyWith("Bom dia!"));

        assertThat(assistant.chat("bom dia")).isEqualTo("Bom dia!");

        assertThat(exported.getFinishedSpanItems()).singleElement().satisfies(span -> {
            // The name has to separate the several AI services a single turn runs — the
            // injection guardrail invokes its own inside the assistant's invocation.
            assertThat(span.getName()).isEqualTo("Assistant.chat");
            assertThat(span.getAttributes().asMap())
                    .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "agent")
                    .containsEntry(LangfuseAttributes.OBSERVATION_INPUT, "\"bom dia\"")
                    .containsEntry(LangfuseAttributes.OBSERVATION_OUTPUT, "\"Bom dia!\"")
                    .containsEntry(GenAiAttributes.OPERATION_NAME, "invoke_agent");
        });
        assertThat(listener.openInvocations()).isZero();
    }

    @Test
    @DisplayName("an invocation that throws still closes its agent observation, at ERROR")
    void aFailingInvocationStillClosesItsObservation() {
        var listener = new LangfuseAiServiceListener(tracer, content, 8);
        var model = new ScriptedChatModel().reply(request -> {
            throw new IllegalStateException("provider refused");
        });
        var assistant = assistant(listener, model);

        assertThatThrownBy(() -> assistant.chat("bom dia"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider refused");

        assertThat(exported.getFinishedSpanItems()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("Assistant.chat");
            assertThat(span.getAttributes().asMap())
                    .containsEntry(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR")
                    .containsEntry(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, "provider refused");
        });

        // A span that is never ended is never exported, so the failing path closing its
        // observation is the whole point; and it removes its entry, so a turn that fails
        // leaves the map exactly as heavy as it found it.
        assertThat(listener.openInvocations()).isZero();
    }

    @Test
    @DisplayName("an invocation that dies without either event is dropped once the bound is reached")
    void aNeverCompletedInvocationIsBoundedRatherThanLeaked() {
        // DefaultAiServices fires its error event from a catch (Exception), so a Throwable
        // that is not an Exception fires neither completed nor error. That is the only way
        // an invocation leaves an entry behind, and the only reason the map is bounded.
        var listener = new LangfuseAiServiceListener(tracer, content, 2);
        // routeBy, not reply: reply() enqueues ONE scripted response and every later call
        // falls through to the fallback, so only the first of the three turns below would
        // have thrown and the other two would have completed normally.
        var model = new ScriptedChatModel().routeBy(request -> {
            throw new StackOverflowError("simulated");
        });
        var assistant = assistant(listener, model);

        for (int turn = 0; turn < 3; turn++) {
            assertThatThrownBy(() -> assistant.chat("bom dia")).isInstanceOf(StackOverflowError.class);
        }

        // Three started, none claimed: the bound holds the two most recent and the oldest
        // is dropped. Eviction cannot be asserted through the exported spans, because an
        // evicted observation is by definition the one that is never exported — it is
        // dropped rather than closed, so that ending it here cannot restore another
        // thread's context onto this one.
        assertThat(listener.openInvocations()).isEqualTo(2);
        assertThat(exported.getFinishedSpanItems()).isEmpty();
    }

    @Test
    @DisplayName("with content capture off, nothing the user typed reaches the agent observation")
    void contentCaptureIsHonoured() {
        var listener = new LangfuseAiServiceListener(
                tracer, new ObservationContentPolicy(false, List.of()), 8);
        var assistant = assistant(listener, new ScriptedChatModel().replyWith("Bom dia!"));

        assistant.chat("meu CPF é 000.000.000-00");

        var span = exported.getFinishedSpanItems().getFirst();
        assertThat(span.getAttributes().asMap().keySet().stream().map(Object::toString))
                .doesNotContain(
                        LangfuseAttributes.OBSERVATION_INPUT.getKey(),
                        LangfuseAttributes.OBSERVATION_OUTPUT.getKey());
        // The shape of the turn survives; only its content is withheld.
        assertThat(span.getAttributes().asMap())
                .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "agent");
    }

    private Assistant assistant(LangfuseAiServiceListener listener, ScriptedChatModel model) {
        return AiServices.builder(Assistant.class)
                .chatModel(model)
                .registerListeners(listener.listeners())
                .build();
    }
}
