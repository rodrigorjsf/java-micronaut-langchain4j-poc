package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;
import io.opentelemetry.api.trace.Span;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * One tool call, observed as a Langfuse {@code tool}.
 *
 * <h2>The observation cannot wrap the execution, and says so</h2>
 * <p>{@code ToolExecutedEvent} fires <em>after</em> the tool has returned — LangChain4j
 * has no before-hook on this listener SPI — so there is no interval to span. This
 * observation opens and closes back to back: its duration is the few microseconds it
 * takes to write these attributes, not the tool's latency. That is stated in the trace
 * as well as here, because a reader who finds a 0 ms {@code tool} observation next to a
 * six-second turn will otherwise conclude the upstream API is fast.
 *
 * <p>Tool latency is not lost, only unattributed: it is inside the parent agent
 * observation. Measuring it per tool would need a {@code beforeToolExecution} hook wired
 * into the AI service, which is a different seam from this one.
 *
 * <h2>Nothing here reports a tool failure, and that is not an omission here</h2>
 * <p>The event exposes the request and the result contents and no error signal. This
 * application configures {@code toolExecutionErrorHandler} to hand the model a sentence
 * instead of an exception, so by the time the event fires a failed call and a successful
 * one are both ordinary text — the distinction was deliberately erased upstream of this
 * listener.
 */
@Singleton
public class LangfuseToolListener implements ToolExecutedEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(LangfuseToolListener.class);

    /**
     * Written into the trace, not only into this file: a near-zero {@code tool} span is
     * indistinguishable from a fast tool unless the trace itself says which it is.
     */
    private static final String TIMING_NOTE =
            "recorded after the tool returned; the span's duration is not the tool's latency";

    private final AgentTracer tracer;
    private final ObservationContentPolicy content;

    public LangfuseToolListener(AgentTracer tracer, ObservationContentPolicy content) {
        this.tracer = tracer;
        this.content = content;
    }

    /**
     * The same shape the other two Langfuse listeners expose, so the AI-service wiring
     * reads identically for all three. A single event type means this class can implement
     * its listener interface directly, which the other two cannot.
     */
    public List<AiServiceListener<?>> listeners() {
        return List.of(this);
    }

    @Override
    public void onEvent(ToolExecutedEvent event) {
        // LangChain4j logs and swallows a listener exception by default, but
        // shouldThrowExceptionOnEventError(true) makes it fatal to the turn. Catching here
        // means the choice of that flag can never turn a tracing bug into a failed turn.
        try {
            observe(event);
        } catch (RuntimeException e) {
            LOG.warn("Could not observe the execution of tool '{}'", event.request().name(), e);
        }
    }

    private void observe(ToolExecutedEvent event) {
        ToolExecutionRequest request = event.request();

        try (Observation observation = tracer.start(request.name(), ObservationType.TOOL)) {
            writeToolIdentity(observation, request);
            observation.genAi(null, GenAiAttributes.OPERATION_EXECUTE_TOOL, null);
            observation.metadata("timing", TIMING_NOTE);

            // The model's own arguments, as the model wrote them, without re-parsing. The
            // string is stored JSON-encoded, which is one level of escaping in the UI, and
            // that is the honest form: these arguments are not always valid JSON — the
            // reason this application configures a toolArgumentsErrorHandler at all — so a
            // parse here would fail on exactly the calls worth looking at.
            observation.input(content.capture(request.arguments()));
            observation.output(content.capture(resultOf(event)));
        }
    }

    /**
     * {@code gen_ai.tool.*} has no method on the {@link Observation} SPI, which exposes
     * the Langfuse fields plus the three descriptive GenAI ones. This class lives in the
     * same package as the OpenTelemetry implementation, so it reaches for the span rather
     * than widening the SPI for two attributes.
     *
     * <p>Guarded by an id comparison rather than trusting {@code Span.current()}:
     * {@code OtelAgentTracer} makes the span it starts current, so the two normally agree,
     * but {@code AgentTracer} is replaceable ({@code @Requires(missingBeans = …)}) and
     * writing this tool's name onto whatever span happened to be current would be worse
     * than not writing it. With another implementation these two attributes are simply
     * absent, and the tool name is the span's name anyway, which is what Langfuse filters
     * on.
     */
    private static void writeToolIdentity(Observation observation, ToolExecutionRequest request) {
        Span span = Span.current();
        if (!span.getSpanContext().getSpanId().equals(observation.ref().observationId())) {
            return;
        }
        span.setAttribute(GenAiAttributes.TOOL_NAME, request.name());
        if (request.id() != null && !request.id().isBlank()) {
            span.setAttribute(GenAiAttributes.TOOL_CALL_ID, request.id());
        }
    }

    /**
     * Never {@code ToolExecutedEvent.resultText()}. It throws {@code IllegalStateException}
     * the moment a result is not exactly one {@link TextContent} — an image, a PDF, or two
     * parts — and a listener exception is swallowed into a log line, so the tool
     * observation would just stop appearing for those calls with nothing red anywhere.
     */
    private static Object resultOf(ToolExecutedEvent event) {
        List<Content> parts = event.resultContents();
        if (parts.size() == 1 && parts.getFirst() instanceof TextContent single) {
            return single.text();
        }
        var described = new ArrayList<String>(parts.size());
        for (Content part : parts) {
            // Binary parts are named, never inlined: a base64 image in a span attribute is
            // megabytes of trace payload for something nobody reads in a Langfuse timeline.
            described.add(part instanceof TextContent text ? text.text() : "[" + part.type() + " content]");
        }
        return described;
    }
}
