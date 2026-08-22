package io.github.rodrigorjsf.agenticchat.observability.trace;

import java.util.Locale;

/**
 * What kind of work an observation represents, in Langfuse's vocabulary.
 *
 * <p>These are not decoration. Langfuse draws an <em>agent graph</em> for a trace only
 * when it holds at least one observation whose type is something other than
 * {@code span}, {@code event} or {@code generation}, so typing the tool call as
 * {@link #TOOL} and the retrieval as {@link #RETRIEVER} is what turns a flat list of
 * spans into the graph. {@link #GENERATION} and {@link #EMBEDDING} are also the only
 * two types that carry usage and cost.
 *
 * <p>The set mirrors {@code ObservationType} in the Langfuse OpenAPI schema exactly
 * (read 2026-08-22 from {@code cloud.langfuse.com/generated/api/openapi.yml}). The
 * wire form is lower case, which is the spelling the attribute-mapping documentation
 * uses.
 */
public enum ObservationType {

    /** A unit of work with a duration and no more specific meaning. */
    SPAN,
    /** A model call: prompts, completions, token usage and cost. */
    GENERATION,
    /** A discrete point in time rather than a duration. */
    EVENT,
    /** Something that decides the application flow. */
    AGENT,
    /** One action — a function or an API call. */
    TOOL,
    /** A link between steps, such as passing context from a retriever to a model. */
    CHAIN,
    /** A lookup that reads and does not change state. */
    RETRIEVER,
    /** A function that assesses another component's output. */
    EVALUATOR,
    /** A call that turns text into vectors; carries usage and cost. */
    EMBEDDING,
    /** A check that protects against malicious content or a jailbreak. */
    GUARDRAIL;

    private final String wireValue = name().toLowerCase(Locale.ROOT);

    public String wireValue() {
        return wireValue;
    }
}
