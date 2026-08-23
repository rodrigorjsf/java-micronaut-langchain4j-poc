package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the conversation-memory layer leaves behind in a trace.
 *
 * <p>Two things used to be wrong at once, and they are separate defects. The observations
 * were typed {@code span}, which says "a unit of work with no more specific meaning" about
 * a step Langfuse has a word for; and they carried no input and no output, so a reader
 * could see that memory had been touched and never what was read or written. A trace that
 * shows a lookup happened but not what came back cannot answer the question people
 * actually open a trace to ask — <em>what history did the model see on this turn?</em>
 *
 * <p><b>The read is a {@code retriever} and the write is NOT.</b> Langfuse's own
 * definition is the reason: a retriever "only looks something up rather than changing
 * state". {@code getMessages} looks something up; {@code updateMessages} writes DynamoDB
 * and then Valkey, and {@code deleteMessages} removes a conversation. Typing those two
 * {@code retriever} would put a write in the agent graph wearing a read's label, which is
 * worse than the {@code span} it already was.
 */
class MemoryObservationTest {

    private static final String CONVERSATION = "conversa-observada";
    private static final List<ChatMessage> HISTORY =
            List.of(UserMessage.from("qual o cep da avenida paulista?"),
                    AiMessage.from("O CEP é 01310-100."));

    private ApplicationContext ctx;
    private InMemorySpanExporter exported;
    private ChatMemoryStore store;
    private final JsonMapper mapper = JsonMapper.createDefault();

    @BeforeEach
    void setUp() {
        Map<String, Object> config = new HashMap<>();
        // The write-through store is the annotated one, and the in-memory backend the rest
        // of the suite runs on replaces it entirely.
        config.put("agentic.persistence.memory-backend", "write-through");
        config.put("agentic.test.fake-memory-stores", "true");
        config.put("agentic.test.record-spans", "true");
        config.put("agentic.llm.credentials.google-api-key", "fake");
        config.put("agentic.llm.credentials.openai-api-key", "fake");

        ctx = ApplicationContext.run(config);
        exported = ctx.getBean(InMemorySpanExporter.class);
        store = ctx.getBean(ChatMemoryStore.class);
        exported.reset();
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private SpanData span(String name) {
        List<SpanData> found = exported.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .toList();
        assertThat(found).as("the '%s' observation", name).isNotEmpty();
        return found.getLast();
    }

    private JsonNode parsed(String value) throws IOException {
        assertThat(value).as("the attribute is written at all").isNotNull();
        return mapper.readValue(value, JsonNode.class);
    }

    @Test
    @DisplayName("a read is a retriever, and its output is the conversation it returned")
    void theReadIsARetriever() throws IOException {
        store.updateMessages(CONVERSATION, HISTORY);
        exported.reset();

        store.getMessages(CONVERSATION);

        var read = span("memory-read");
        assertThat(read.getAttributes().get(LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("retriever");
        assertThat(read.getAttributes().get(LangfuseAttributes.OBSERVATION_INPUT))
                .as("the lookup key, which is what a retriever's input is")
                .contains(CONVERSATION);

        JsonNode returned = parsed(read.getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT));
        assertThat(returned.isArray()).as("the messages, as JSON and not as a toString").isTrue();
        assertThat(returned.size()).isEqualTo(2);
        assertThat(returned.get(1).get("text").getStringValue()).isEqualTo("O CEP é 01310-100.");
    }

    @Test
    @DisplayName("a write is a span, and its input is the conversation being persisted")
    void theWriteIsASpanCarryingWhatItWrote() throws IOException {
        store.updateMessages(CONVERSATION, HISTORY);

        var write = span("memory-write");
        assertThat(write.getAttributes().get(LangfuseAttributes.OBSERVATION_TYPE))
                .as("a write changes state, so it is not a retriever")
                .isEqualTo("span");

        // The interceptor hands a two-argument method its arguments as a positional list.
        JsonNode arguments = parsed(write.getAttributes().get(LangfuseAttributes.OBSERVATION_INPUT));
        assertThat(arguments.get(0).getStringValue()).isEqualTo(CONVERSATION);
        assertThat(arguments.get(1).size()).as("the conversation, parseable").isEqualTo(2);
        assertThat(arguments.get(1).get(0).get("contents").get(0).get("text").getStringValue())
                .isEqualTo("qual o cep da avenida paulista?");
    }

    @Test
    @DisplayName("a delete is a span too, and says which conversation it removed")
    void theDeleteIsASpan() {
        store.deleteMessages(CONVERSATION);

        var deleted = span("memory-delete");
        assertThat(deleted.getAttributes().get(LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("span");
        assertThat(deleted.getAttributes().get(LangfuseAttributes.OBSERVATION_INPUT)).contains(CONVERSATION);
    }
}
