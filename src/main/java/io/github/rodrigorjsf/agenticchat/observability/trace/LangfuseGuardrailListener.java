package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.GuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.observability.api.event.GuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.InputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.OutputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.observability.api.listener.InputGuardrailExecutedListener;
import dev.langchain4j.observability.api.listener.OutputGuardrailExecutedListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every guardrail execution, on the way in and on the way out, as a Langfuse
 * {@code guardrail}.
 *
 * <h2>Why the listeners are nested classes instead of this class implementing them</h2>
 * <p>{@code InputGuardrailExecutedListener} and {@code OutputGuardrailExecutedListener}
 * both extend {@code AiServiceListener<E>} with a different {@code E}, and Java forbids
 * one class from inheriting the same generic interface twice with different type
 * arguments — it does not compile. Both are therefore fields, built once so that
 * {@code unregisterListener}, which matches on identity, can find them again. Named
 * classes rather than lambdas because LangChain4j's registrar logs
 * {@code listener.getClass().getName()} when it swallows an exception.
 *
 * <p>Wire it with {@code registerListeners(bean.listeners())}.
 *
 * <h2>N observations per turn is the right number</h2>
 * <p>{@code OutputGuardrailExecutor} re-runs the <em>whole</em> chain against the new
 * response on every reprompt, firing one event per guardrail per attempt, and
 * {@code VoiceComplianceGuardrail} reprompts by design. So the same guardrail appearing
 * three times in one turn is the trace being accurate, and deduplicating them would hide
 * the retries — which are the expensive part, since each one is another model call.
 * There is deliberately no deduplication anywhere below.
 *
 * <h2>The timing is honest about what it can measure</h2>
 * <p>Like the tool listener, this one fires after the work is done, so the span is a
 * point rather than an interval. Unlike the tool listener it does not have to guess:
 * {@code GuardrailExecutedEvent.duration()} carries what the executor measured, and it
 * is written as metadata. An LLM-backed guardrail — this application has one — is
 * seconds; a regular-expression one is microseconds, which is why the unit is
 * milliseconds with a fraction rather than a truncated integer.
 */
@Singleton
public class LangfuseGuardrailListener {

    private static final Logger LOG = LoggerFactory.getLogger(LangfuseGuardrailListener.class);

    private final AgentTracer tracer;
    private final ObservationContentPolicy content;
    private final List<AiServiceListener<?>> listeners;

    public LangfuseGuardrailListener(AgentTracer tracer, ObservationContentPolicy content) {
        this.tracer = tracer;
        this.content = content;
        this.listeners = List.of(new Input(), new Output());
    }

    /**
     * The two listeners to hand to {@code AiServices.registerListeners(…)}. Always the
     * same instances, so they can be unregistered.
     */
    public List<AiServiceListener<?>> listeners() {
        return listeners;
    }

    private final class Input implements InputGuardrailExecutedListener {

        @Override
        public void onEvent(InputGuardrailExecutedEvent event) {
            // LangChain4j logs and swallows a listener exception by default, but
            // shouldThrowExceptionOnEventError(true) makes it fatal to the turn. Catching
            // here means the choice of that flag can never cost a user their answer.
            try {
                observe(event, textOf(event.request().userMessage()));
            } catch (RuntimeException e) {
                LOG.warn("Could not observe input guardrail '{}'", event.guardrailName(), e);
            }
        }
    }

    private final class Output implements OutputGuardrailExecutedListener {

        @Override
        public void onEvent(OutputGuardrailExecutedEvent event) {
            try {
                observe(event, textOf(event.request().responseFromLLM().aiMessage()));
            } catch (RuntimeException e) {
                LOG.warn("Could not observe output guardrail '{}'", event.guardrailName(), e);
            }
        }
    }

    private void observe(GuardrailExecutedEvent<?, ?, ?> event, String checked) {
        GuardrailResult<?> result = event.result();

        try (Observation observation = tracer.start(event.guardrailName(), ObservationType.GUARDRAIL)) {
            // The verdict is not content: it is the thing anyone querying this trace
            // filters on ("every FATAL guardrail last week"), so it goes in metadata,
            // which Langfuse keeps filterable, and it is written whether or not content
            // capture is on.
            observation.metadata("guardrail_result", result.result().name());

            // Guarded because the event builder does not require a duration, and losing
            // the whole observation over a missing one would trade the guardrail's verdict
            // for its latency.
            if (event.duration() != null) {
                observation.metadata("duration_ms", event.duration().toNanos() / 1_000_000d);
            }

            observation.input(content.capture(checked));
            observation.output(content.capture(verdictOf(result)));

            if (!result.isSuccess()) {
                // WARNING and not ERROR, including for FATAL. A guardrail that blocks is
                // the guardrail working; ERROR in Langfuse means something went wrong, and
                // a trace where every blocked injection is red teaches people to ignore
                // red. The turn's own failure, when a fatal guardrail causes one, is
                // recorded at ERROR on the agent observation by LangfuseAiServiceListener.
                observation.level(ObservationLevel.WARNING, summaryOf(result));
            }
        }
    }

    /**
     * What the guardrail decided, as the observation's output: the verdict, the rewritten
     * text when it rewrote one, its failure messages, and the reprompt it asked for.
     *
     * <p>The failure messages are the guardrails' own wording rather than exception text,
     * and they still go through {@link ObservationContentPolicy} with everything else —
     * a message that quotes what it objected to would otherwise carry the user's words
     * past a switch that was set to keep them in the process.
     */
    private static Map<String, Object> verdictOf(GuardrailResult<?> result) {
        var verdict = new LinkedHashMap<String, Object>();
        verdict.put("result", result.result().name());

        if (result.hasRewrittenResult()) {
            // A rewriting guardrail — this application normalises every incoming message
            // with one — has no other way to show what it did.
            verdict.put("rewritten", result.successfulText());
        }

        List<String> failures = messagesOf(result);
        if (!failures.isEmpty()) {
            verdict.put("failures", failures);
        }

        // A reprompt reports FATAL, which reads like a block and is not one. In
        // LangChain4j's vocabulary fatal means "stop evaluating the rest of the chain on
        // this pass"; whether the turn then retries or gives up is carried by the failure's
        // retry flag, not by the verdict. This key is what separates the two in the trace.
        if (result instanceof OutputGuardrailResult output && output.isReprompt()) {
            output.getReprompt().ifPresent(reprompt -> verdict.put("reprompt", reprompt));
        }
        return verdict;
    }

    private static String summaryOf(GuardrailResult<?> result) {
        List<String> failures = messagesOf(result);
        return failures.isEmpty() ? result.result().name() : String.join("; ", failures);
    }

    /**
     * No null guard on the list: {@code GuardrailResult} is sealed to the two
     * implementations in LangChain4j, and both normalise a null failure list to an empty
     * one in their constructor. A message can still be null, and that one is guarded.
     */
    private static List<String> messagesOf(GuardrailResult<?> result) {
        List<GuardrailResult.Failure> failures = result.failures();
        return failures.stream().map(GuardrailResult.Failure::message).filter(Objects::nonNull).toList();
    }

    /**
     * Not {@code UserMessage.singleText()}: it throws when the message carries an image or
     * several parts, and a listener exception is swallowed into a log line — the guardrail
     * observation would just stop appearing, with nothing red anywhere.
     */
    private static String textOf(UserMessage message) {
        var text = new StringBuilder();
        for (Content part : message.contents()) {
            if (part instanceof TextContent textPart) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textPart.text());
            }
        }
        return text.toString();
    }

    /**
     * An {@link AiMessage} that only asked for a tool has no text at all, and an output
     * guardrail can be handed one.
     */
    private static String textOf(AiMessage message) {
        return message == null ? null : message.text();
    }
}
