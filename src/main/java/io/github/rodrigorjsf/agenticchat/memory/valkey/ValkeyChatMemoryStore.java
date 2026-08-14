package io.github.rodrigorjsf.agenticchat.memory.valkey;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.github.rodrigorjsf.agenticchat.infra.config.PersistenceProperties;
import io.github.rodrigorjsf.agenticchat.memory.ConversationId;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * Chat memory in Valkey: one string key per conversation, expiring on the
 * configured conversation TTL so idle sessions cost nothing.
 *
 * <p>Used as the read path of {@code WriteThroughChatMemoryStore}, not on its own —
 * an eviction here must not lose a conversation.
 */
@Singleton
@Named("valkey")
public class ValkeyChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "agentic:conv:";

    private final StatefulRedisConnection<String, String> connection;
    private final PersistenceProperties props;

    public ValkeyChatMemoryStore(StatefulRedisConnection<String, String> connection,
                                 PersistenceProperties props) {
        this.connection = connection;
        this.props = props;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var json = connection.sync().get(key(memoryId));
        return json == null ? List.of() : ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        connection.sync().setex(
                key(memoryId),
                props.conversationTtl().toSeconds(),
                ChatMessageSerializer.messagesToJson(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        connection.sync().del(key(memoryId));
    }

    private String key(Object memoryId) {
        // ConversationId validates the raw value, so the key cannot be crafted to
        // collide with, or glob-match, another conversation's key.
        return KEY_PREFIX + ConversationId.of(memoryId).value();
    }
}
