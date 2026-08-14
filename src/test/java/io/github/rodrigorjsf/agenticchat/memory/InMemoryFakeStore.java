package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test double that can be told to fail, so the degradation paths are testable. */
final class InMemoryFakeStore implements ChatMemoryStore {

    private final Map<String, List<ChatMessage>> data = new HashMap<>();
    boolean failReads;
    boolean failWrites;
    int writes;
    int reads;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        reads++;
        if (failReads) {
            throw new IllegalStateException("simulated read failure");
        }
        return List.copyOf(data.getOrDefault(String.valueOf(memoryId), List.of()));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        writes++;
        if (failWrites) {
            throw new IllegalStateException("simulated write failure");
        }
        data.put(String.valueOf(memoryId), new ArrayList<>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        data.remove(String.valueOf(memoryId));
    }

    boolean has(Object memoryId) {
        return data.containsKey(String.valueOf(memoryId));
    }
}
