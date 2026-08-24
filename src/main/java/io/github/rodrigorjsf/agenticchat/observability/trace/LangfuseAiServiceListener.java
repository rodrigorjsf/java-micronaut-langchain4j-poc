package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.listener.AiServiceCompletedListener;
import dev.langchain4j.observability.api.listener.AiServiceErrorListener;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.observability.api.listener.AiServiceStartedListener;
import dev.langchain4j.service.Result;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * One AI-service invocation, observed as a Langfuse {@code agent}.
 *
 * <p>The type is load-bearing rather than decorative. Langfuse draws an agent graph for
 * a trace only when it holds an observation typed something other than {@code span},
 * {@code event} or {@code generation}, so this observation plus the {@code tool} and
 * {@code guardrail} ones around it are what turn a flat list of spans into the picture
 * of a turn.
 *
 * <h2>Why the listeners are nested classes instead of this class implementing them</h2>
 * <p>{@code AiServiceStartedListener}, {@code AiServiceCompletedListener} and
 * {@code AiServiceErrorListener} all extend {@code AiServiceListener<E>} with a
 * different {@code E}, and Java forbids one class from inheriting the same generic
 * interface twice with different type arguments — it does not compile. The three
 * listeners are therefore fields, built once in the constructor so that
 * {@code unregisterListener}, which matches on identity, can find them again. Named
 * classes rather than lambdas because LangChain4j's registrar logs
 * {@code listener.getClass().getName()} when it swallows an exception, and
 * {@code LangfuseAiServiceListener$Started} is a diagnostic where
 * {@code …$$Lambda$42} is not.
 *
 * <p>Wire it with {@code registerListeners(bean.listeners())}.
 *
 * <h2>The map, and what happens when a completion never arrives</h2>
 * <p>The started event and the completed event are different objects, so the open
 * observation is carried between them by {@code invocationId}. Both the completed and
 * the error listener remove their entry, which is why an ordinary failing turn leaks
 * nothing. The bound exists for the one case neither covers: {@code DefaultAiServices}
 * fires its error event from a {@code catch (Exception)}, so a {@code StackOverflowError}
 * or an {@code OutOfMemoryError} raised between the two events fires neither, and that
 * entry is never claimed.
 *
 * <p>An evicted entry is <b>dropped, not closed</b>. Its observation holds an
 * OpenTelemetry scope attached to the thread that started it; ending it from the
 * evicting thread would restore that other thread's context here and mis-parent every
 * span the evicting thread starts afterwards. So the evicted span is never ended and
 * therefore never exported — a missing observation, which is the failure this class
 * prefers to a corrupted trace. Nothing reachable from here can do better: that turn's
 * scope was already stranded on its own thread the moment its completion went missing.
 *
 * <h2>One turn can hold several of these, and that is correct</h2>
 * <p>{@code InjectionTriageGuardrail} runs its own AI service <em>inside</em> the
 * assistant's invocation, so a single user turn legitimately produces more than one
 * agent observation. The name — the AI-service interface plus the method — is what
 * tells them apart in the UI, which is why it is not a constant.
 *
 * <h2>No usage and no cost here</h2>
 * <p>{@code Result} carries a {@code TokenUsage} and it is tempting. Langfuse accepts
 * {@code usage_details} and {@code cost_details} on {@code generation} and
 * {@code embedding} observations only, and {@code TokenCostListener} already reports
 * the model call that produced those tokens. Writing them here would either be ignored
 * or counted twice.
 */
@Singleton
public class LangfuseAiServiceListener {

    private static final Logger LOG = LoggerFactory.getLogger(LangfuseAiServiceListener.class);

    private final AgentTracer tracer;
    private final ObservationContentPolicy content;

    private final Map<UUID, Observation> open = new ConcurrentHashMap<>();

    /**
     * The ids in {@link #open}, in the order they were started, so that eviction takes the
     * oldest rather than an arbitrary entry. Kept in step with the map on every path: an
     * id left here after its invocation finished would let the bound count invocations
     * that are over, and the queue would then evict a <em>live</em> observation while dead
     * ids held the capacity — a turn silently losing its span because enough short ones
     * ran inside it.
     *
     * <p>{@code size()} and {@code remove(Object)} are both O(n) here and {@code size()} is
     * not a snapshot, which is irrelevant at a few hundred entries and would stop being
     * irrelevant if the bound grew — raise it and this needs a counter beside it.
     */
    private final Deque<UUID> startOrder = new ConcurrentLinkedDeque<>();

    private final int maxOpenInvocations;
    private final List<AiServiceListener<?>> listeners;

    public LangfuseAiServiceListener(
            AgentTracer tracer,
            ObservationContentPolicy content,
            @Value("${agentic.observability.max-open-invocations:512}") int maxOpenInvocations) {
        this.tracer = tracer;
        this.content = content;
        this.maxOpenInvocations = Math.max(1, maxOpenInvocations);
        this.listeners = List.of(new Started(), new Completed(), new Errored());
    }

    /**
     * The three listeners to hand to {@code AiServices.registerListeners(…)}. Always the
     * same instances, so they can be unregistered.
     */
    public List<AiServiceListener<?>> listeners() {
        return listeners;
    }

    /**
     * How many invocations are open. Exists for the test that pins the bound: eviction
     * is invisible in the exported spans by construction, since an evicted observation
     * is precisely the one that is never exported.
     */
    int openInvocations() {
        return open.size();
    }

    private final class Started implements AiServiceStartedListener {

        @Override
        public void onEvent(AiServiceStartedEvent event) {
            // LangChain4j logs and swallows a listener exception by default, but
            // shouldThrowExceptionOnEventError(true) makes it fatal to the turn. Catching
            // here means the choice of that flag can never turn a tracing bug into a 5xx.
            try {
                onStarted(event);
            } catch (RuntimeException e) {
                LOG.warn("Could not open the agent observation for {}",
                        nameOf(event.invocationContext()), e);
            }
        }
    }

    private final class Completed implements AiServiceCompletedListener {

        @Override
        public void onEvent(AiServiceCompletedEvent event) {
            try {
                onCompleted(event);
            } catch (RuntimeException e) {
                LOG.warn("Could not close the agent observation for {}",
                        nameOf(event.invocationContext()), e);
            }
        }
    }

    private final class Errored implements AiServiceErrorListener {

        @Override
        public void onEvent(AiServiceErrorEvent event) {
            try {
                onError(event);
            } catch (RuntimeException e) {
                LOG.warn("Could not close the failed agent observation for {}",
                        nameOf(event.invocationContext()), e);
            }
        }
    }

    private void onStarted(AiServiceStartedEvent event) {
        InvocationContext invocation = event.invocationContext();
        Observation observation = tracer.start(nameOf(invocation), ObservationType.AGENT);

        // Registered and bounded before it is decorated. Reversing these two leaves an
        // open span that nobody holds a reference to if an attribute write throws, and an
        // unended span is never exported.
        open.put(invocation.invocationId(), observation);
        startOrder.addLast(invocation.invocationId());
        evictOverflow();

        describe(observation, event, invocation);
    }

    private void describe(Observation observation, AiServiceStartedEvent event, InvocationContext invocation) {
        observation.genAi(providerOf(invocation), GenAiAttributes.OPERATION_INVOKE_AGENT, modelOf(invocation));
        observation.metadata("chat_memory_id", chatMemoryIdOf(invocation));

        // The user message only. The system prompt is on the started event, and this
        // application's first invariant is that it is byte-identical across turns — so
        // copying it onto every agent observation would multiply a ~2100-token document
        // by the number of traces and say nothing new. Which prompt was in force is
        // already on every span as langfuse.version and langfuse.release.
        observation.input(content.capture(textOf(event.userMessage())));
    }

    private void onCompleted(AiServiceCompletedEvent event) {
        try (Observation observation = claim(event.invocationContext().invocationId())) {
            if (observation == null) {
                return;
            }
            observation.output(content.capture(resultOf(event)));
        }
        // In a finally so that a serialisation failure still ends the span. An
        // observation that is not closed is not exported at all.
    }

    private void onError(AiServiceErrorEvent event) {
        try (Observation observation = claim(event.invocationContext().invocationId())) {
            if (observation == null) {
                // DefaultAiServices fires the error event from the proxy's catch block, which
                // wraps more than the block that fires the started event: a failure while
                // validating the AI-service method's parameters arrives here with no
                // observation to fail. There is nothing to close, and inventing a span for it
                // would report a turn that never began.
                return;
            }
            observation.failed(event.error());
        }
    }

    /**
     * Takes the observation out of both structures. Out of {@link #startOrder} as well as
     * out of {@link #open}, so that the bound keeps counting <em>open</em> invocations —
     * see the field's own note for what leaving it behind would evict instead.
     */
    private Observation claim(UUID invocationId) {
        startOrder.remove(invocationId);
        return open.remove(invocationId);
    }

    private void evictOverflow() {
        while (startOrder.size() > maxOpenInvocations) {
            UUID oldest = startOrder.pollFirst();
            if (oldest == null) {
                return;
            }
            // The null case is a race with claim() on another thread, not a routine one:
            // every id still queued here belongs to an invocation that has not finished.
            if (open.remove(oldest) != null) {
                LOG.warn("Dropping the agent observation for invocation {}: it never completed and the "
                        + "bound of {} open invocations was reached. Its span will not be exported.",
                        oldest, maxOpenInvocations);
            }
        }
    }

    /**
     * The AI-service interface's simple name plus the method — {@code ChatAssistant.chat}.
     * Trimmed past {@code $} as well as {@code .} because a nested interface's binary name
     * carries both.
     */
    private static String nameOf(InvocationContext invocation) {
        String declaring = invocation.interfaceName();
        int cut = Math.max(declaring.lastIndexOf('.'), declaring.lastIndexOf('$'));
        return declaring.substring(cut + 1) + '.' + invocation.methodName();
    }

    /**
     * Unwraps {@link Result} before the payload reaches the serializer. A {@code Result}
     * carries the token usage, the retrieved sources, every tool execution and the raw
     * {@code ChatResponse}es; Micronaut Serde has never been told about it, so
     * {@link ObservationJson} would fall back to {@code toString} and copy most of the
     * trace into one attribute of one span. The answer is the content.
     */
    private static Object resultOf(AiServiceCompletedEvent event) {
        Object result = event.result().orElse(null);
        return result instanceof Result<?> typed ? typed.content() : result;
    }

    /**
     * Not {@code UserMessage.singleText()}: it throws when the message carries an image
     * or several parts, and a listener exception is swallowed into a log line — the
     * observation would simply stop appearing, with nothing red anywhere.
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

    private static String chatMemoryIdOf(InvocationContext invocation) {
        Object memoryId = invocation.chatMemoryId();
        return memoryId == null ? null : memoryId.toString();
    }

    /**
     * {@code gen_ai.system} in the spelling the GenAI conventions use — lower snake case,
     * matching the {@code google_genai} this project already writes on generations.
     */
    private static String providerOf(InvocationContext invocation) {
        var provider = invocation.modelProvider();
        return provider == null ? null : provider.name().toLowerCase(Locale.ROOT);
    }

    private static String modelOf(InvocationContext invocation) {
        var parameters = invocation.defaultRequestParameters();
        return parameters == null ? null : parameters.modelName();
    }
}
