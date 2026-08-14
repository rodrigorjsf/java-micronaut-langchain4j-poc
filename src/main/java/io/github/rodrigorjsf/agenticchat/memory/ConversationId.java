package io.github.rodrigorjsf.agenticchat.memory;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identity of one conversation. A value object rather than a bare String because
 * it is used to build storage keys, and an unvalidated id there is a key-injection
 * hole: {@code "a\nb"} or {@code "*"} in a Valkey key, or a crafted DynamoDB
 * partition key, lets one conversation read or clobber another's memory.
 */
public record ConversationId(String value) {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public ConversationId {
        if (value == null || !SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "conversation id must match [A-Za-z0-9_-]{1,64}");
        }
    }

    public static ConversationId newId() {
        return new ConversationId(UUID.randomUUID().toString().replace("-", ""));
    }

    /** Accepts anything LangChain4j hands back as a {@code @MemoryId}. */
    public static ConversationId of(Object memoryId) {
        return memoryId instanceof ConversationId id ? id : new ConversationId(String.valueOf(memoryId));
    }

    @Override
    public String toString() {
        return value;
    }
}
