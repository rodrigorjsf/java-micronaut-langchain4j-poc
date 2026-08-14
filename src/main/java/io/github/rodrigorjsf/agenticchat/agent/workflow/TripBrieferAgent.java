package io.github.rodrigorjsf.agenticchat.agent.workflow;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Step 3: compose the three findings into one short briefing.
 *
 * <p>No tools. It only reads what the earlier agents wrote into the shared scope,
 * which is what keeps the raw API responses out of the main conversation.
 */
public interface TripBrieferAgent {

    @SystemMessage("""
            You compose a short travel briefing from findings that were already
            gathered. You have no tools and must not invent anything that is not in
            the findings. Write in Brazilian Portuguese. Keep advice practical and to
            one sentence.
            """)
    @UserMessage("""
            Place: {{resolvedPlace}}
            Weather: {{weatherReport}}
            Holiday: {{holidayNote}}""")
    @Agent(name = "trip_briefer",
            description = "Composes the final briefing",
            outputKey = "briefing")
    TripBriefing brief(@V("resolvedPlace") String resolvedPlace,
                       @V("weatherReport") String weatherReport,
                       @V("holidayNote") String holidayNote);
}
