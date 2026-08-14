package io.github.rodrigorjsf.agenticchat.agent.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;

/**
 * What the trip-briefing workflow returns to the main agent.
 *
 * <p>Structured and short by design. The whole reason this workflow exists is that
 * the main conversation should receive a briefing, not the raw JSON of three
 * public APIs — that raw text would land in chat memory and be replayed into
 * every later prompt for the rest of the conversation.
 */
public record TripBriefing(

        @JsonProperty(required = true)
        @Description("The resolved place: city, region and country")
        String place,

        @JsonProperty(required = true)
        @Description("Two or three sentences on the weather, with the WMO code already translated")
        String weather,

        @JsonProperty(required = true)
        @Description("Whether the date is a Brazilian national holiday, and which one")
        String holiday,

        @JsonProperty(required = true)
        @Description("One sentence of practical advice that follows from the two above")
        String advice) {
}
