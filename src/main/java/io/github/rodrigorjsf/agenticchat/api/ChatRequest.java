package io.github.rodrigorjsf.agenticchat.api;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param conversationId an existing conversation to continue; a new one is started
 *                       when absent. Validated by {@code ConversationId} before it
 *                       reaches any storage key.
 * @param message        the user's turn. The size bound is a cost control as much
 *                       as a validation rule: the guardrails cap the same length,
 *                       but rejecting here costs nothing at all.
 */
@Serdeable
public record ChatRequest(
        @Nullable @Size(max = 64) String conversationId,
        @NotBlank @Size(max = 12_000) String message) {
}
