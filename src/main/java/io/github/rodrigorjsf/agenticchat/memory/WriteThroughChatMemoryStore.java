package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The conversation memory the application actually uses: Valkey in front of
 * DynamoDB.
 *
 * <p>DynamoDB is the source of truth and Valkey is a cache, which fixes the
 * ordering: writes go to DynamoDB first, then to Valkey. If the Valkey write
 * fails the key is deleted rather than left holding a previous value, so the next
 * read falls through to DynamoDB instead of serving a stale conversation — a
 * stale chat memory is worse than a slow one, because the model then answers from
 * a history the user did not have.
 *
 * <p>Availability is asymmetric on purpose: Valkey being down degrades latency
 * only, while DynamoDB being down fails the request. Silently continuing without
 * the durable store would drop a user's conversation with no signal.
 */
@Singleton
@Primary
@Requires(property = "agentic.persistence.memory-backend", value = "write-through", defaultValue = "write-through")
public class WriteThroughChatMemoryStore implements ChatMemoryStore {

    private static final Logger LOG = LoggerFactory.getLogger(WriteThroughChatMemoryStore.class);

    private final ChatMemoryStore cache;
    private final ChatMemoryStore durable;

    public WriteThroughChatMemoryStore(@Named("valkey") ChatMemoryStore cache,
                                       @Named("dynamodb") ChatMemoryStore durable) {
        this.cache = cache;
        this.durable = durable;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try {
            var cached = cache.getMessages(memoryId);
            if (!cached.isEmpty()) {
                return cached;
            }
        } catch (RuntimeException e) {
            LOG.warn("Valkey read failed for conversation {}, falling back to DynamoDB", memoryId, e);
        }

        var messages = durable.getMessages(memoryId);
        if (!messages.isEmpty()) {
            warm(memoryId, messages);
        }
        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        durable.updateMessages(memoryId, messages);
        try {
            cache.updateMessages(memoryId, messages);
        } catch (RuntimeException e) {
            LOG.warn("Valkey write failed for conversation {}, invalidating the cache entry", memoryId, e);
            invalidate(memoryId);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        durable.deleteMessages(memoryId);
        invalidate(memoryId);
    }

    private void warm(Object memoryId, List<ChatMessage> messages) {
        try {
            cache.updateMessages(memoryId, messages);
        } catch (RuntimeException e) {
            LOG.warn("Could not warm the Valkey entry for conversation {}", memoryId, e);
        }
    }

    private void invalidate(Object memoryId) {
        try {
            cache.deleteMessages(memoryId);
        } catch (RuntimeException e) {
            // Nothing better to do: the entry expires on its own TTL.
            LOG.warn("Could not invalidate the Valkey entry for conversation {}", memoryId, e);
        }
    }
}
