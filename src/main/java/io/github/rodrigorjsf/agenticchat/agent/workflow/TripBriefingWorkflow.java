package io.github.rodrigorjsf.agenticchat.agent.workflow;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.tools.brazil.BrazilCivicDataTools;
import io.github.rodrigorjsf.agenticchat.tools.geo.GeoWeatherTools;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * A composed sub-agent workflow: resolve, then look up two independent things at
 * once, then summarise.
 *
 * <pre>
 *   place_resolver ──▶ ┌ weather_reporter ┐ ──▶ trip_briefer
 *                      └ holiday_checker  ┘
 * </pre>
 *
 * <p><b>Why this is a workflow and not three tool calls.</b> Two reasons, and the
 * first is the one that matters:
 *
 * <ol>
 *   <li><b>Context isolation.</b> Each sub-agent calls its tools, reads the raw
 *       JSON, and writes a sentence into the shared scope. The main conversation
 *       receives a four-field briefing instead of three fat API responses — and
 *       the main conversation is the one that persists in chat memory and gets
 *       replayed into every later prompt. Doing this inline would put roughly
 *       20 KB of JSON somewhere it can never be evicted from.</li>
 *   <li><b>Real parallelism.</b> The weather report and the holiday check share no
 *       inputs, so they run concurrently. The place resolution genuinely comes
 *       first, because the weather agent needs its coordinates.</li>
 * </ol>
 *
 * <p><b>What it costs, stated plainly.</b> Four model calls per briefing. That is
 * the honest price of the isolation above, and it is why this is exposed as one
 * tool the main agent chooses to call rather than something on the default path.
 * A single "trip briefing" question is worth it; a plain "vai chover?" is not, and
 * should go through {@code geo-and-weather} instead.
 *
 * <p>Composed programmatically rather than with {@code @SequenceAgent} and friends.
 * Those annotations require every supplier to be a {@code static} method, which
 * fights constructor injection: the statics would have to reach a global
 * application context to find a model or a tool bean. Building here keeps the
 * dependencies explicit and the wiring greppable.
 */
@Singleton
public class TripBriefingWorkflow {

    private static final Logger LOG = LoggerFactory.getLogger(TripBriefingWorkflow.class);

    private final UntypedAgent workflow;
    private final MeterRegistry meters;

    public TripBriefingWorkflow(ChatModelRegistry models,
                                BrazilCivicDataTools brazilTools,
                                GeoWeatherTools geoTools,
                                MeterRegistry meters,
                                @jakarta.inject.Named("agentic-workflow") ExecutorService executor) {
        this.meters = meters;

        var model = models.forRole("agent");

        var placeResolver = AgenticServices.agentBuilder(PlaceResolverAgent.class)
                .chatModel(model)
                .tools(geoTools)
                .maxToolCallingRoundTrips(3)
                .build();

        var weatherReporter = AgenticServices.agentBuilder(WeatherReporterAgent.class)
                .chatModel(model)
                .tools(geoTools)
                .maxToolCallingRoundTrips(3)
                .build();

        var holidayChecker = AgenticServices.agentBuilder(HolidayCheckerAgent.class)
                .chatModel(model)
                .tools(brazilTools)
                .maxToolCallingRoundTrips(3)
                .build();

        var briefer = AgenticServices.agentBuilder(TripBrieferAgent.class)
                .chatModel(model)
                .build();

        // The parallel stage takes an explicit executor. The default would be a
        // common pool, and a workflow that blocks on two public APIs has no business
        // occupying threads shared with everything else in the JVM.
        var lookups = AgenticServices.parallelBuilder()
                .subAgents(weatherReporter, holidayChecker)
                .executor(executor)
                .build();

        this.workflow = AgenticServices.sequenceBuilder()
                .subAgents(placeResolver, lookups, briefer)
                .outputKey("briefing")
                .errorHandler(errorContext -> {
                    // One handler for the whole system — AgentBuilder has none. Never
                    // retry: retry() here is unbounded and recurses until the stack ends.
                    LOG.warn("Sub-agent '{}' failed in the trip briefing workflow",
                            errorContext.agentName(), errorContext.exception());
                    meters.counter("agentic.workflow.errors",
                            "workflow", "trip_briefing",
                            "agent", String.valueOf(errorContext.agentName())).increment();
                    return dev.langchain4j.agentic.agent.ErrorRecoveryResult.throwException();
                })
                .build();
    }

    /**
     * @param place a place name, e.g. "Florianópolis"
     * @param date  an ISO date, e.g. 2026-09-07
     */
    public TripBriefing brief(String place, String date) {
        var sample = Timer.start(meters);
        try {
            Object result = workflow.invoke(Map.of("place", place, "date", date));
            sample.stop(meters.timer("agentic.workflow.latency", "workflow", "trip_briefing", "outcome", "ok"));
            return (TripBriefing) result;
        } catch (RuntimeException e) {
            sample.stop(meters.timer("agentic.workflow.latency", "workflow", "trip_briefing", "outcome", "error"));
            throw e;
        }
    }
}
