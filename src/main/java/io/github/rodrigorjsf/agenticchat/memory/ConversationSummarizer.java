package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * Compresses older conversation into a short summary.
 *
 * <p>An interface so compaction can be tested without a model, and so the
 * summariser can be turned off in a degraded deployment without touching the
 * algorithm around it.
 */
public interface ConversationSummarizer {

    /**
     * @return a summary of at most a few hundred tokens, or an empty string when
     * summarisation is unavailable — the caller then keeps the messages
     * rather than losing them
     */
    String summarize(List<ChatMessage> messages);
}
