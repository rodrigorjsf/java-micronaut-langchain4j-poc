package io.github.rodrigorjsf.agenticchat.api;

import io.github.rodrigorjsf.agenticchat.conversation.ChatTurn;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * What a caller gets back.
 *
 * <p>Reports how the turn was handled and which tools ran, because a chat API that
 * only returns text is impossible to debug from the outside. It reports no reason
 * for a refusal beyond the outcome: telling a caller which rule fired turns the
 * endpoint into an oracle for probing the defences.
 */
@Serdeable
public record ChatResponse(String conversationId,
                           String reply,
                           String outcome,
                           String intent,
                           List<String> toolsUsed,
                           Usage usage) {

    @Serdeable
    public record Usage(int inputTokens, int outputTokens) {
    }

    static ChatResponse of(String conversationId, ChatTurn turn) {
        var usage = turn.usage() == null ? null : new Usage(
                orZero(turn.usage().inputTokenCount()),
                orZero(turn.usage().outputTokenCount()));
        return new ChatResponse(
                conversationId,
                turn.reply(),
                turn.outcome().name(),
                turn.verdict().intent(),
                turn.toolsUsed(),
                usage);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
