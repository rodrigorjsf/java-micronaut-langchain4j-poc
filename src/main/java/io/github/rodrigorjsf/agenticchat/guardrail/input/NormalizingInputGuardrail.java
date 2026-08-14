package io.github.rodrigorjsf.agenticchat.guardrail.input;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import jakarta.inject.Singleton;

/**
 * Position 1 in the input chain. Rewrites, never blocks.
 *
 * <p>It has to run first because LangChain4j feeds each guardrail's
 * {@code successfulText()} to every subsequent guardrail, and the rewritten
 * message is also what reaches the model. Normalizing here means every later rule
 * — and the model itself — sees one canonical form, so no rule has to be written
 * twice for the fullwidth and zero-width variants of the same attack.
 *
 * <p>Multi-part messages are passed through untouched: LangChain4j's
 * {@code rewriteUserMessage} replaces <em>every</em> {@code TextContent} with the
 * same string, which would destroy a message that has more than one.
 */
@Singleton
public class NormalizingInputGuardrail implements InputGuardrail {

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        if (!userMessage.hasSingleText()) {
            return success();
        }
        String raw = userMessage.singleText();
        if (raw == null || raw.isBlank()) {
            return success();
        }
        String normalized = TextNormalizer.normalize(raw);
        return normalized.equals(raw) ? success() : successWith(normalized);
    }
}
