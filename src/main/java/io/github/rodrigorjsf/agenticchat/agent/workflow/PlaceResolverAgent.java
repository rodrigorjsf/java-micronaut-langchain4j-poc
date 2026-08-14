package io.github.rodrigorjsf.agenticchat.agent.workflow;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Step 1: turn a place name into coordinates.
 *
 * <p>An AI agent rather than a direct tool call because the interesting part is
 * judgement, not lookup: {@code find_place} returns several candidates and there
 * is a São Paulo in Brazil and one in Portugal. Picking between them, or saying
 * plainly that it is ambiguous, is what the model is for.
 */
public interface PlaceResolverAgent {

    @SystemMessage("""
            You resolve a place name to coordinates using the find_place tool.

            Return exactly one line in this form, and nothing else:
            <city>, <region>, <country> | <latitude> | <longitude>

            If the top candidates are in different countries, pick the one in Brazil
            when there is one, otherwise the most populous. Never invent coordinates:
            if the tool returns nothing, return the single word UNKNOWN.
            """)
    @UserMessage("Place: {{place}}")
    @Agent(name = "place_resolver",
            description = "Resolves a place name to coordinates",
            outputKey = "resolvedPlace")
    String resolve(@V("place") String place);
}
