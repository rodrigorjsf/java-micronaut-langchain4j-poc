package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Summarises with the <b>judge</b> model, not the agent model.
 *
 * <p>Compression is not reasoning. The judge model measured a 0.91 s median
 * against 5.93 s for the agent model on this machine, and compaction runs at the
 * end of a turn where latency is still latency — see
 * {@code docs/adr/0006-llm-as-judge-triage.md}.
 *
 * <p>Returns an empty string on any failure. The compactor then keeps the messages
 * uncompressed, which is worse for the token budget and strictly better than
 * losing a conversation because a summariser was rate-limited.
 */
@Singleton
public class LlmConversationSummarizer implements ConversationSummarizer {

    private static final Logger LOG = LoggerFactory.getLogger(LlmConversationSummarizer.class);
    private static final String NOTHING = "NOTHING";

    private final SummarizerPrompt prompt;

    public LlmConversationSummarizer(SummarizerPrompt prompt) {
        this.prompt = prompt;
    }

    @Override
    public String summarize(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        try {
            String summary = prompt.summarize(render(messages));
            if (summary == null || summary.isBlank() || NOTHING.equalsIgnoreCase(summary.strip())) {
                return "";
            }
            return summary.strip();
        } catch (RuntimeException e) {
            LOG.warn("Summarisation failed; the conversation is kept uncompressed", e);
            return "";
        }
    }

    /**
     * Roles are labelled so the summariser can tell who established a fact. Tool
     * results carry their tool name, which is what makes the summary attributable.
     */
    private static String render(List<ChatMessage> messages) {
        var out = new StringBuilder();
        for (ChatMessage message : messages) {
            switch (message) {
                case UserMessage user -> out.append("USER: ")
                        .append(user.hasSingleText() ? user.singleText() : "(multipart)").append('\n');
                case AiMessage ai -> {
                    if (ai.text() != null && !ai.text().isBlank()) {
                        out.append("ASSISTANT: ").append(ai.text()).append('\n');
                    }
                }
                case ToolExecutionResultMessage result -> out.append("TOOL ")
                        .append(result.toolName()).append(": ").append(result.text()).append('\n');
                case SystemMessage ignored -> {
                    // The system prompt is never summarised; it is re-supplied every turn.
                }
                default -> out.append(message).append('\n');
            }
        }
        return out.toString();
    }
}
