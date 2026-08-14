package io.github.rodrigorjsf.agenticchat.conversation;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import io.github.rodrigorjsf.agenticchat.triage.TriageVerdict;

import java.util.List;

/**
 * The outcome of one turn, with enough detail for the response, the metrics and the
 * trace — and nothing an attacker could use to map the defences.
 *
 * @param reply     what the user sees
 * @param outcome   which path produced it
 * @param verdict   the triage decision, for logging and evals
 * @param toolsUsed tool names actually executed, so a caller can see the work
 * @param usage     token usage, {@code null} when no model was called
 */
public record ChatTurn(String reply,
                       Outcome outcome,
                       TriageVerdict verdict,
                       List<String> toolsUsed,
                       TokenUsage usage) {

    public enum Outcome {
        /** The agent answered. */
        ANSWERED,
        /** Triage judged the request outside the assistant's scope. */
        REFUSED,
        /** A guardrail stopped the turn on the way in or on the way out. */
        BLOCKED
    }

    public ChatTurn {
        toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
    }

    static ChatTurn refused(TriageVerdict verdict) {
        return new ChatTurn(verdict.outOfScopeReply(), Outcome.REFUSED, verdict, List.of(), null);
    }

    static ChatTurn blocked(TriageVerdict verdict, String reply) {
        return new ChatTurn(reply, Outcome.BLOCKED, verdict, List.of(), null);
    }

    static ChatTurn answered(TriageVerdict verdict, Result<String> result) {
        var tools = result.toolExecutions() == null ? List.<String>of()
                : result.toolExecutions().stream()
                        .map(execution -> execution.request().name())
                        .distinct()
                        .toList();
        return new ChatTurn(result.content(), Outcome.ANSWERED, verdict, tools, result.tokenUsage());
    }
}
