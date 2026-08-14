package io.github.rodrigorjsf.agenticchat.tools.http;

/**
 * What a tool hands back to the model.
 *
 * <p>Failures are values, not exceptions. A thrown exception reaches the model as
 * LangChain4j's default tool-error text, which is {@code Throwable.getMessage()} —
 * stack traces, upstream URLs and response bodies included. Returning a shaped
 * result keeps that data out of the prompt, the chat history and the provider's
 * logs, and gives the model something it can actually act on.
 *
 * @param body      the payload to show the model, already truncated
 * @param outcome   what happened, in terms the model can reason about
 * @param truncated whether {@code body} is shorter than what the API returned
 */
public record ToolResponse(String body, Outcome outcome, boolean truncated) {

    public enum Outcome {
        /**
         * The upstream answered and the body is usable.
         */
        OK,
        /**
         * The upstream answered "no such thing" — a valid answer, not a failure.
         */
        NOT_FOUND,
        /**
         * The arguments were rejected. The model can fix this by retrying differently.
         */
        INVALID_REQUEST,
        /**
         * Rate limited. Retrying the same call immediately will fail again.
         */
        RATE_LIMITED,
        /**
         * The upstream failed or timed out. Nothing the model can do about it.
         */
        UPSTREAM_ERROR
    }

    public static ToolResponse ok(String body, boolean truncated) {
        return new ToolResponse(body, Outcome.OK, truncated);
    }

    public static ToolResponse failure(Outcome outcome, String message) {
        return new ToolResponse(message, outcome, false);
    }

    public boolean isOk() {
        return outcome == Outcome.OK;
    }

    /**
     * The exact string the model sees. Every branch says what happened and what to
     * do next, because a tool result that only says "error" makes the model retry
     * the same call or invent an answer.
     */
    public String toModelText() {
        return switch (outcome) {
            case OK -> truncated
                    ? body + "\n\n[truncated: the response was longer than this tool's budget. "
                      + "Narrow the query if you need the rest.]"
                    : body;
            case NOT_FOUND -> "No result: " + body
                    + " Tell the user nothing was found; do not guess a value.";
            case INVALID_REQUEST -> "Invalid arguments: " + body
                    + " Correct the arguments and call the tool again, or ask the user for the missing detail.";
            case RATE_LIMITED -> "Rate limited: " + body
                    + " Do not retry this tool now. Answer from what you already have, or say the service is busy.";
            case UPSTREAM_ERROR -> "The service is unavailable: " + body
                    + " Do not retry. Tell the user this data source is temporarily unavailable.";
        };
    }
}
