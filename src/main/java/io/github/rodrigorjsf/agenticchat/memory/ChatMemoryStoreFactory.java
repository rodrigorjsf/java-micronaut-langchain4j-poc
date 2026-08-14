package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The in-process memory backend, selected with
 * {@code agentic.persistence.memory-backend: in-memory}.
 *
 * <p>It exists so the application can run and be tested with no Docker, no floci and
 * no network — which is what keeps the test suite fast and keeps a first-time reader
 * from needing infrastructure to see the thing work.
 *
 * <p>It is not a mock and not a fallback: nothing selects it automatically. Silently
 * degrading to process-local memory when Valkey is unreachable would lose every
 * conversation on a restart while the health check stayed green, so the choice is
 * explicit and the default is durable.
 */
@Factory
@Requires(property = "agentic.persistence.memory-backend", value = "in-memory")
public class ChatMemoryStoreFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ChatMemoryStoreFactory.class);

    @Singleton
    @Primary
    ChatMemoryStore inMemoryChatMemoryStore() {
        LOG.warn("Conversation memory is in-process: conversations are lost on restart "
                + "and are not shared between instances");
        return new InMemoryChatMemoryStore();
    }
}
