package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an observation payload looks like once it reaches Langfuse.
 *
 * <p>Every assertion here PARSES the result rather than searching it for a substring, and
 * that is the point of the class. A conversation written with {@code toString} contains the
 * message text too — {@code [UserMessage { contents = [TextContent { text = "oi" }] }]} —
 * so a {@code contains("oi")} assertion passes on the broken rendering as happily as on the
 * correct one. Parsing is what tells them apart.
 */
class ObservationJsonTest {

    private final ObservationJson json = ObservationJson.compact();
    private final JsonMapper mapper = JsonMapper.createDefault();

    private JsonNode parsed(String value) throws IOException {
        return mapper.readValue(value, JsonNode.class);
    }

    @Test
    @DisplayName("a conversation is written as JSON messages, not as a Java toString")
    void aConversationIsWrittenAsJson() throws IOException {
        List<ChatMessage> conversation = List.of(UserMessage.from("oi"), AiMessage.from("ola"));

        JsonNode written = parsed(json.write(conversation));

        assertThat(written.isArray()).as("an array of messages").isTrue();
        assertThat(written.size()).isEqualTo(2);
        assertThat(written.get(0).get("type").getStringValue()).isEqualTo("USER");
        assertThat(written.get(1).get("type").getStringValue()).isEqualTo("AI");
        assertThat(written.get(1).get("text").getStringValue()).isEqualTo("ola");
    }

    @Test
    @DisplayName("the interceptor's multi-argument shape keeps the conversation as JSON inside it")
    void aConversationNestedInAnArgumentListSurvives() throws IOException {
        // What ObservedInterceptor.argumentsOf produces for
        // updateMessages(Object memoryId, List<ChatMessage> messages): a positional list
        // whose SECOND element is the conversation. A rule that only recognised a bare
        // list of messages would miss this one, which is the input of `memory-write`.
        Object arguments = List.of("conversa-1", List.of(UserMessage.from("oi")));

        JsonNode written = parsed(json.write(arguments));

        assertThat(written.get(0).getStringValue()).isEqualTo("conversa-1");
        assertThat(written.get(1).isArray()).as("the conversation, still an array").isTrue();
        // A UserMessage carries `contents`, not `text` — the real LangChain4j shape, not a
        // convenient one. Reading it here is what proves the message went through
        // ChatMessageSerializer and not through anything this repository invented.
        JsonNode message = written.get(1).get(0);
        assertThat(message.get("type").getStringValue()).isEqualTo("USER");
        assertThat(message.get("contents").get(0).get("text").getStringValue()).isEqualTo("oi");
    }

    @Test
    @DisplayName("a single message is written as one JSON object")
    void oneMessage() throws IOException {
        JsonNode written = parsed(json.write(UserMessage.from("oi")));

        assertThat(written.get("type").getStringValue()).isEqualTo("USER");
    }

    @Test
    @DisplayName("everything the seams already passed is unchanged")
    void theNarrowContractStillHolds() {
        assertThat(json.write("texto")).isEqualTo("\"texto\"");
        assertThat(json.write(Map.of("decision", "IN_SCOPE"))).isEqualTo("{\"decision\":\"IN_SCOPE\"}");
        assertThat(json.write(List.of(1, 2))).isEqualTo("[1,2]");
        assertThat(json.write(null)).isNull();
    }
}
