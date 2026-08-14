package io.github.rodrigorjsf.agenticchat.guardrail.output;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses to deliver a response that carries the system-prompt canary.
 *
 * <p>Uses {@code fatalWithMessageRemoval} rather than {@code failure}: the
 * offending {@link AiMessage} is deleted from chat memory before the exception
 * propagates. Leaving it in memory would mean the leaked text is replayed into the
 * prompt on the next turn, so a single successful extraction would keep leaking
 * for the rest of the conversation.
 */
@Singleton
public class SystemPromptLeakageGuardrail implements OutputGuardrail {

    private static final Logger LOG = LoggerFactory.getLogger(SystemPromptLeakageGuardrail.class);

    private final SystemPromptCanary canary;

    public SystemPromptLeakageGuardrail(SystemPromptCanary canary) {
        this.canary = canary;
    }

    @Override
    public OutputGuardrailResult validate(AiMessage response) {
        if (response == null || response.text() == null) {
            return success();
        }
        if (canary.leakedIn(response.text())) {
            LOG.error("System prompt leakage detected: the response contained the integrity marker");
            return fatalWithMessageRemoval("The response was withheld by the output policy.");
        }
        return success();
    }
}
