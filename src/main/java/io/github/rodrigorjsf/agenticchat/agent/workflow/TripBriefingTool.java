package io.github.rodrigorjsf.agenticchat.agent.workflow;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.rodrigorjsf.agenticchat.skills.SkillTools;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The sub-agent workflow, exposed to the main agent as one tool.
 *
 * <p>It lives beside the workflow rather than under {@code tools.*} because it is
 * the workflow's adapter, not a data source. Putting it with the other tools would
 * also create a genuine cycle — the workflow needs tool beans, and this needs the
 * workflow — which the architecture test catches rather than tolerates.
 *
 * <p>"Agent as a tool" rather than a second entry point: the main agent already
 * decides what a turn needs, and giving it one more callable thing is a smaller
 * change than teaching the pipeline about a second kind of request. It also means
 * the workflow inherits every guardrail and every bound that already applies to a
 * tool call.
 */
@Singleton
public class TripBriefingTool implements SkillTools {

    private static final Logger LOG = LoggerFactory.getLogger(TripBriefingTool.class);
    private static final int MAX_FORECAST_DAYS = 7;

    private final TripBriefingWorkflow workflow;
    private final java.time.Clock clock;

    public TripBriefingTool(TripBriefingWorkflow workflow, java.time.Clock clock) {
        this.workflow = workflow;
        this.clock = clock;
    }

    @Override
    public String skillName() {
        return "trip-briefing";
    }

    @Tool("""
            Produce a combined briefing for a place on a date: the resolved location, \
            the weather in plain language, whether it is a Brazilian national holiday, \
            and one line of advice. Only use this when the user asked about a place AND \
            a date together; a plain weather or holiday question is cheaper through the \
            other skills.""")
    public String trip_briefing(
            @P("The place name, e.g. 'Florianópolis'. City names work best.")
            String place,
            @P("The date in ISO format, e.g. 2026-09-07. Must be within the next 7 days.")
            String date) {

        if (place == null || place.isBlank()) {
            return "Invalid arguments: a place name is required.";
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(date == null ? "" : date.strip());
        } catch (DateTimeParseException notADate) {
            return "Invalid arguments: expected an ISO date such as 2026-09-07, got '" + date + "'.";
        }

        var today = LocalDate.now(clock);
        if (parsed.isBefore(today) || parsed.isAfter(today.plusDays(MAX_FORECAST_DAYS))) {
            return "Invalid arguments: the forecast only reaches " + MAX_FORECAST_DAYS
                    + " days ahead, and " + parsed + " is outside that window. "
                    + "Tell the user the date is too far out.";
        }

        try {
            var briefing = workflow.brief(place.strip(), parsed.toString());
            return """
                    Local: %s
                    Clima: %s
                    Feriado: %s
                    Dica: %s""".formatted(
                    briefing.place(), briefing.weather(), briefing.holiday(), briefing.advice());
        } catch (RuntimeException e) {
            LOG.error("Trip briefing workflow failed for place={} date={}", place, parsed, e);
            return "The briefing could not be completed. Tell the user this feature is "
                    + "temporarily unavailable and offer the weather and holiday lookups separately.";
        }
    }
}
