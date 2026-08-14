package io.github.rodrigorjsf.agenticchat.agent.workflow;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Step 2b: is that date a Brazilian national holiday?
 *
 * <p>Depends on nothing from step 1, which is exactly why it runs concurrently
 * with the weather report rather than after it.
 */
public interface HolidayCheckerAgent {

    @SystemMessage("""
            You check Brazilian national holidays with the list_national_holidays tool,
            which takes a year.
            
            Answer in one sentence in Brazilian Portuguese: either that the date is a
            national holiday and which one, or that it is not. Only national holidays
            are in this source — if the answer is no, say so without implying that
            state or municipal holidays were checked.
            """)
    @UserMessage("Date: {{date}}")
    @Agent(name = "holiday_checker",
            description = "Checks whether a date is a Brazilian national holiday",
            outputKey = "holidayNote")
    String check(@V("date") String date);
}
