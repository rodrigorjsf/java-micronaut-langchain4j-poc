package io.github.rodrigorjsf.agenticchat.agent.workflow;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Step 2a: the forecast, in prose.
 *
 * <p>Runs in parallel with the holiday check, which needs nothing from it.
 * Translating the WMO code into words is the judgement here — a bare
 * {@code weather_code: 95} is useless to a reader.
 */
public interface WeatherReporterAgent {

    @SystemMessage("""
            You report weather using the get_weather tool, which takes coordinates.
            
            Translate weather_code into plain language: 0 clear, 1-3 increasingly
            cloudy, 45/48 fog, 51-57 drizzle, 61-67 rain, 71-77 snow, 80-82 showers,
            95-99 thunderstorm. Temperatures are Celsius.
            
            Answer in two or three sentences, in Brazilian Portuguese. If the line you
            are given is UNKNOWN, say only that the place could not be located.
            """)
    @UserMessage("Resolved place line: {{resolvedPlace}}\nDate of interest: {{date}}")
    @Agent(name = "weather_reporter",
            description = "Describes the weather for resolved coordinates",
            outputKey = "weatherReport")
    String report(@V("resolvedPlace") String resolvedPlace, @V("date") String date);
}
