package io.github.rodrigorjsf.agenticchat.memory.dynamodb;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.github.rodrigorjsf.agenticchat.infra.config.PersistenceProperties;
import io.github.rodrigorjsf.agenticchat.memory.ConversationId;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Durable chat memory in one DynamoDB item per conversation.
 *
 * <p>One item, not one per message: {@link ChatMemoryStore#updateMessages} replaces
 * the whole list on every turn, so a per-message layout would mean a read-diff-write
 * on the hot path for no benefit. A windowed memory is far below the 400 KB item cap.
 *
 * <p>Serialization goes through LangChain4j's own
 * {@link ChatMessageSerializer}. That is deliberate and load-bearing: it preserves
 * {@code ToolExecutionResultMessage} attributes, which is where the tool-search
 * {@code found_tools} bookkeeping lives. A store that persisted only
 * {@code message.text()} would silently reset progressive tool disclosure on every
 * turn.
 */
@Singleton
@Named("dynamodb")
public class DynamoDbChatMemoryStore implements ChatMemoryStore {

    static final String SORT_KEY = "MEMORY";
    private static final String KEY_PREFIX = "CONV#";

    private final DynamoDbClient dynamoDb;
    private final PersistenceProperties props;
    private final Clock clock;

    public DynamoDbChatMemoryStore(DynamoDbClient dynamoDb, PersistenceProperties props, Clock clock) {
        this.dynamoDb = dynamoDb;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var item = dynamoDb.getItem(b -> b
                .tableName(props.tableName())
                .key(key(memoryId))
                .consistentRead(true)).item();

        if (item == null || item.isEmpty()) {
            return List.of();
        }
        var json = item.get("messages");
        return json == null ? List.of() : ChatMessageDeserializer.messagesFromJson(json.s());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        var expiresAt = clock.instant().plus(props.conversationTtl()).getEpochSecond();
        dynamoDb.putItem(b -> b
                .tableName(props.tableName())
                .item(Map.of(
                        "pk", AttributeValue.fromS(KEY_PREFIX + ConversationId.of(memoryId).value()),
                        "sk", AttributeValue.fromS(SORT_KEY),
                        "messages", AttributeValue.fromS(ChatMessageSerializer.messagesToJson(messages)),
                        "expires_at", AttributeValue.fromN(Long.toString(expiresAt)))));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        dynamoDb.deleteItem(b -> b.tableName(props.tableName()).key(key(memoryId)));
    }

    private Map<String, AttributeValue> key(Object memoryId) {
        return Map.of(
                "pk", AttributeValue.fromS(KEY_PREFIX + ConversationId.of(memoryId).value()),
                "sk", AttributeValue.fromS(SORT_KEY));
    }
}
