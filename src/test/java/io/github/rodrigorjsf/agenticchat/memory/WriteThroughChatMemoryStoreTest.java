package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WriteThroughChatMemoryStoreTest {

    private static final String ID = "conv1";
    private static final List<ChatMessage> MESSAGES =
            List.of(UserMessage.from("oi"), AiMessage.from("ola"));

    private InMemoryFakeStore cache;
    private InMemoryFakeStore durable;
    private WriteThroughChatMemoryStore store;

    @BeforeEach
    void setUp() {
        cache = new InMemoryFakeStore();
        durable = new InMemoryFakeStore();
        store = new WriteThroughChatMemoryStore(cache, durable);
    }

    @Test
    void returnsEmptyForAnUnknownConversation() {
        assertThat(store.getMessages(ID)).isEmpty();
    }

    @Test
    void writesReachBothStores() {
        store.updateMessages(ID, MESSAGES);

        assertThat(durable.has(ID)).isTrue();
        assertThat(cache.has(ID)).isTrue();
        assertThat(store.getMessages(ID)).isEqualTo(MESSAGES);
    }

    @Test
    void readsAreServedFromTheCacheWithoutTouchingTheDurableStore() {
        store.updateMessages(ID, MESSAGES);
        int durableReadsBefore = durable.reads;

        assertThat(store.getMessages(ID)).isEqualTo(MESSAGES);
        assertThat(durable.reads).isEqualTo(durableReadsBefore);
    }

    @Test
    void aCacheMissFallsThroughToTheDurableStoreAndWarmsTheCache() {
        durable.updateMessages(ID, MESSAGES);

        assertThat(store.getMessages(ID)).isEqualTo(MESSAGES);
        assertThat(cache.has(ID)).as("cache warmed after the miss").isTrue();
    }

    @Test
    void aCacheReadFailureDegradesToTheDurableStore() {
        durable.updateMessages(ID, MESSAGES);
        cache.failReads = true;

        assertThat(store.getMessages(ID)).isEqualTo(MESSAGES);
    }

    @Test
    void aCacheWriteFailureStillPersistsAndLeavesNoStaleEntry() {
        store.updateMessages(ID, List.of(UserMessage.from("primeira")));
        cache.failWrites = true;

        store.updateMessages(ID, MESSAGES);

        assertThat(durable.getMessages(ID)).isEqualTo(MESSAGES);
        assertThat(cache.has(ID)).as("stale cache entry must be invalidated, never left behind").isFalse();
        assertThat(store.getMessages(ID)).isEqualTo(MESSAGES);
    }

    @Test
    void aDurableWriteFailureIsNotSwallowed() {
        durable.failWrites = true;

        assertThatThrownBy(() -> store.updateMessages(ID, MESSAGES))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deleteRemovesFromBothStores() {
        store.updateMessages(ID, MESSAGES);

        store.deleteMessages(ID);

        assertThat(durable.has(ID)).isFalse();
        assertThat(cache.has(ID)).isFalse();
        assertThat(store.getMessages(ID)).isEmpty();
    }

    @Test
    void conversationsAreIsolatedFromEachOther() {
        store.updateMessages("a", List.of(UserMessage.from("de a")));
        store.updateMessages("b", List.of(UserMessage.from("de b")));

        assertThat(store.getMessages("a")).containsExactly(UserMessage.from("de a"));
        assertThat(store.getMessages("b")).containsExactly(UserMessage.from("de b"));
    }
}
