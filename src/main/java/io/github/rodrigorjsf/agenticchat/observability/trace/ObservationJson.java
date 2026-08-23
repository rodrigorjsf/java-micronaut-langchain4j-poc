package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Turns an observation's input or output into the JSON string Langfuse stores.
 *
 * <p>The contract with the seams is deliberately narrow: pass a {@code String}, a
 * number, a boolean, a {@code Map} or a {@code List} of those. Micronaut Serde is a
 * compile-time serializer, so an arbitrary domain object it has never been told about
 * fails at runtime rather than at build time — and a failure here must never be able to
 * break the call it was only observing. Anything it refuses is written as its
 * {@code toString}, and the reason is logged once per call at debug.
 *
 * <p><b>{@link ChatMessage} is the one exception, and it earns it.</b> The conversation
 * is what the memory layer's observations carry, and Serde has never been told about
 * LangChain4j's message hierarchy — so the narrow contract alone would write a whole
 * conversation as {@code "[UserMessage { contents = [TextContent { text = \"oi\" }] }]"}:
 * a Java {@code toString} wrapped in a JSON string, unparseable by anything reading the
 * trace. LangChain4j already ships the encoder ({@link ChatMessageSerializer}), it is the
 * same one {@code ValkeyChatMemoryStore} and {@code DynamoDbChatMemoryStore} persist with,
 * and it keeps the tool calls and message attributes a hand-rolled projection would drop.
 *
 * <p>The substitution is RECURSIVE, and that is not tidiness. {@code ObservedInterceptor}
 * hands a multi-argument method its arguments as a positional list, so the input of
 * {@code memory-write} arrives as {@code [memoryId, List<ChatMessage>]} — the conversation
 * is the second ELEMENT, not the value. A rule that only recognised a bare list of
 * messages would leave exactly that observation broken.
 */
@Singleton
public class ObservationJson {

    private static final Logger LOG = LoggerFactory.getLogger(ObservationJson.class);

    private final JsonMapper mapper;

    public ObservationJson(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * For tests and for any caller outside the bean container.
     */
    public static ObservationJson compact() {
        return new ObservationJson(JsonMapper.createDefault());
    }

    /**
     * @return the JSON form, or {@code null} when there is nothing to write — the
     * caller then writes no attribute at all rather than the string "null"
     */
    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(serializable(value));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Falling back to toString for observation payload of type {}",
                    value.getClass().getName(), e);
            return '"' + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
    }

    /**
     * Replaces every {@link ChatMessage} in the value with the JSON tree LangChain4j
     * writes for it, at whatever depth it sits, and leaves everything else alone.
     */
    private Object serializable(Object value) {
        if (value instanceof ChatMessage message) {
            return tree(ChatMessageSerializer.messageToJson(message), message);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::serializable).toList();
        }
        return value;
    }

    private Object tree(String json, Object original) {
        try {
            return mapper.readValue(json, JsonNode.class);
        } catch (IOException | RuntimeException e) {
            // Re-reading what LangChain4j just wrote should not fail, and if it does the
            // observation is still not allowed to be the thing that breaks the call.
            LOG.debug("Could not re-read the serialized form of {}", original.getClass().getName(), e);
            return String.valueOf(original);
        }
    }
}
