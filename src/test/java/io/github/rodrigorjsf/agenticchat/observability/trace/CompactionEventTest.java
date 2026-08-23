package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.github.rodrigorjsf.agenticchat.memory.ConversationCompactor;
import io.github.rodrigorjsf.agenticchat.memory.ConversationId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The moment a conversation was compacted, as an exported span.
 *
 * <p>Compaction is the one step in this application whose effect is felt in a
 * <em>later</em> turn: the answer that lost context did not lose it in its own trace.
 * A duration would be the wrong shape for that — nobody waits for compaction, it runs
 * after the turn is delivered — so it is an {@link ObservationType#EVENT}, a discrete
 * fact carrying the numbers that explain the later answer.
 *
 * <p>The seam is the exported span, like every other tracing test here. The counts are
 * asserted against the store's own state rather than against the compactor's arithmetic,
 * so an event that reported the wrong number would fail rather than agree with itself.
 */
class CompactionEventTest {

    private static final int TRIGGER = 500;

    private InMemorySpanExporter exported;
    private SdkTracerProvider tracerProvider;
    private AgentTracer tracer;
    private FakeStore store;
    private ConversationCompactor compactor;

    /** Enough of a {@link ChatMemoryStore} to compact against, and nothing else. */
    private static final class FakeStore implements ChatMemoryStore {

        private final Map<Object, List<ChatMessage>> data = new HashMap<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return new ArrayList<>(data.getOrDefault(memoryId, List.of()));
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            data.put(memoryId, List.copyOf(messages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            data.remove(memoryId);
        }
    }

    @BeforeEach
    void setUp() {
        exported = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exported))
                .build();
        tracer = new OtelAgentTracer(tracerProvider.get("test"), ObservationJson.compact());
        store = new FakeStore();
        compactor = new ConversationCompactor(
                store, messages -> "- the user asked about CEPs", new SimpleMeterRegistry(), TRIGGER, tracer);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    /**
     * Long enough to cross {@link #TRIGGER}, and shaped like a real one: a system
     * message, a skill activation that must survive, and a dozen tool-backed exchanges.
     */
    private static List<ChatMessage> longConversation() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from("system prompt"));
        messages.add(ToolExecutionResultMessage.builder()
                .id("act-1").toolName("activate_skill").text("Activated brazil-civic-data")
                .attributes(Map.of("activated_skill", "brazil-civic-data"))
                .build());
        for (int i = 0; i < 12; i++) {
            messages.add(UserMessage.from("pergunta numero " + i + " ".repeat(60)));
            messages.add(ToolExecutionResultMessage.builder()
                    .id("cep-" + i).toolName("lookup_cep")
                    .text("{\"cep\":\"0131010" + i + "\"}" + "x".repeat(300))
                    .build());
            messages.add(AiMessage.from("resposta numero " + i + " ".repeat(60)));
        }
        return messages;
    }

    private List<SpanData> events() {
        return exported.getFinishedSpanItems().stream()
                .filter(span -> "event".equals(span.getAttributes().get(LangfuseAttributes.OBSERVATION_TYPE)))
                .toList();
    }

    @Test
    @DisplayName("a compaction event sits under the observation that was open, and has no duration")
    void aCompactionEventIsAChildAndNotADuration() {
        var id = new ConversationId("conversa-aninhada");
        store.updateMessages(id.value(), longConversation());

        // In the application this parent is the CHAIN the @Observed interceptor opens
        // around compactIfNeeded; here it stands for whatever was open at the time.
        String parentSpanId;
        try (Observation turn = tracer.start("chat-turn", ObservationType.AGENT)) {
            parentSpanId = turn.ref().observationId();
            compactor.compactIfNeeded(id);
        }

        var event = events().getFirst();
        // An event that opened its own root trace would still export, still carry the
        // right type and still read correctly on its own — and would be missing from the
        // only trace anyone would look in for it.
        assertThat(event.getParentSpanId()).isEqualTo(parentSpanId);
        assertThat(event.getAttributes().asMap())
                // Lower case on the wire. An upper-case value does not error: Langfuse
                // files it as a plain span and the event stops being an event.
                .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "event");

        // A point in time, not an interval. Bounded rather than asserted equal to zero:
        // SimpleSpanProcessor timestamps a real start and a real end.
        assertThat(event.getEndEpochNanos() - event.getStartEpochNanos())
                .isLessThan(java.time.Duration.ofMillis(50).toNanos());
    }

    @Test
    @DisplayName("a compaction event carries counts and none of the conversation it summarised away")
    void aCompactionEventCarriesNoContent() {
        var conversation = new ArrayList<>(longConversation());
        conversation.add(UserMessage.from("meu CPF e 000.000.000-00"));
        var id = new ConversationId("conversa-sigilosa");
        store.updateMessages(id.value(), conversation);

        compactor.compactIfNeeded(id);

        // On VALUES, not on attribute names, for the reason TurnTraceShapeTest states: a
        // key added to this event later is covered by the assertion for free. What the
        // compaction dropped is the conversation, and a measurement is not the place any
        // of it travels — content in this application goes through ObservationContentPolicy.
        var values = events().getFirst().getAttributes().asMap().values().stream()
                .map(String::valueOf)
                .toList();

        assertThat(values).isNotEmpty();
        assertThat(values).noneMatch(value -> value.contains("000.000.000-00"));
        assertThat(values).noneMatch(value -> value.contains("pergunta numero"));
        assertThat(values).noneMatch(value -> value.contains("the user asked about CEPs"));
    }

    @Test
    @DisplayName("a compaction is exported as an event carrying what went in and what came out")
    void aCompactionIsAnEventWithItsCounts() {
        var conversation = longConversation();
        var id = new ConversationId("conversa-compactada");
        store.updateMessages(id.value(), conversation);

        compactor.compactIfNeeded(id);

        // Asserted against the store, not against the compactor's own arithmetic: an
        // event that reported a number nothing else produced would otherwise agree with
        // itself and pass.
        int after = store.getMessages(id.value()).size();
        assertThat(after).isLessThan(conversation.size());

        assertThat(events()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("memory-compacted");
            assertThat(span.getAttributes().asMap())
                    .containsEntry(LangfuseAttributes.observationMetadata("messages_before"),
                            String.valueOf(conversation.size()))
                    .containsEntry(LangfuseAttributes.observationMetadata("messages_after"),
                            String.valueOf(after));

            // A relation, not the arithmetic: recomputing chars/4 here would be the
            // estimator agreeing with itself. What the event has to be right about is
            // the direction — a compaction that grew the conversation is a bug, and a
            // pair of numbers that never moved is an event nobody can act on.
            long tokensBefore = Long.parseLong(span.getAttributes()
                    .get(LangfuseAttributes.observationMetadata("tokens_before")));
            long tokensAfter = Long.parseLong(span.getAttributes()
                    .get(LangfuseAttributes.observationMetadata("tokens_after")));
            assertThat(tokensAfter).isLessThan(tokensBefore);
        });
    }
}
