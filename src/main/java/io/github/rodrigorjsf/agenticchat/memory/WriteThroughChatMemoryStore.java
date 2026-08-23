package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.github.rodrigorjsf.agenticchat.observability.trace.Observed;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationType;
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
 *
 * <p><b>The read is a {@code retriever} and the two writes are not.</b> Langfuse
 * reserves {@code retriever} for "a data-retrieval step that only looks something up
 * rather than changing state, such as a call to a vector store, database, or other
 * knowledge source" — which is {@link #getMessages} exactly, and is not
 * {@link #updateMessages} or {@link #deleteMessages}, both of which write DynamoDB
 * and then Valkey. Those stay {@code span}, whose meaning is "a unit of work with no
 * more specific meaning": none of the other nine types describes a store write, and
 * typing one {@code retriever} would draw a write into the agent graph wearing a
 * read's label.
 *
 * <p>All three capture their content, and that is a deliberate exception to this
 * application's default. {@code @Observed} captures nothing unless asked because a
 * turn's arguments are the user's message; here the arguments and the result ARE the
 * conversation, and an observation of the memory layer that omits it records that a
 * lookup happened while withholding the one fact a reader opened the trace for — what
 * history the model was given. {@code ObservationContentPolicy} still governs it, so
 * {@code agentic.observability.capture-content: false} turns all of it off in one
 * place, and {@code ObservationJson} writes the messages through LangChain4j's own
 * serializer rather than as a Java {@code toString}.
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
    @Observed(value = "memory-read", type = ObservationType.RETRIEVER,
            captureArguments = true, captureResult = true)
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
    // No captureResult: the method returns void, and an output attribute reading
    // "null" is a worse answer than no attribute.
    @Observed(value = "memory-write", type = ObservationType.SPAN, captureArguments = true)
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
    @Observed(value = "memory-delete", type = ObservationType.SPAN, captureArguments = true)
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
