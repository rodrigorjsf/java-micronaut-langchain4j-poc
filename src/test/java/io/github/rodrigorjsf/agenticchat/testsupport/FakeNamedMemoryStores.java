package io.github.rodrigorjsf.agenticchat.testsupport;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import io.github.rodrigorjsf.agenticchat.memory.dynamodb.DynamoDbChatMemoryStore;
import io.github.rodrigorjsf.agenticchat.memory.valkey.ValkeyChatMemoryStore;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * The two halves of the write-through store, without Valkey and without DynamoDB.
 *
 * <p>It exists so the real {@code WriteThroughChatMemoryStore} — the one carrying the
 * {@code @Observed} annotations — can be exercised through the bean container, which is
 * the only way its AOP advice runs at all. The default test profile selects the
 * {@code in-memory} backend, so that class and its observations are absent from every
 * other test in the suite; a test that wants them has to ask for this backend, and a
 * test that asks for this backend without Docker needs these two beans.
 *
 * <p>Off unless {@code agentic.test.fake-memory-stores} is set, so it cannot quietly
 * stand in for the real stores anywhere else.
 */
@Factory
@Requires(property = "agentic.test.fake-memory-stores", value = "true")
public class FakeNamedMemoryStores {

    @Singleton
    @Named("valkey")
    @Replaces(ValkeyChatMemoryStore.class)
    ChatMemoryStore fakeCache() {
        return new InMemoryChatMemoryStore();
    }

    @Singleton
    @Named("dynamodb")
    @Replaces(DynamoDbChatMemoryStore.class)
    ChatMemoryStore fakeDurable() {
        return new InMemoryChatMemoryStore();
    }
}
