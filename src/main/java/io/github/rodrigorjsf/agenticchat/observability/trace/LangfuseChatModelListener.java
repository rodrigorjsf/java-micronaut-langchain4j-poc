package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import io.github.rodrigorjsf.agenticchat.observability.CostCalculator;
import io.github.rodrigorjsf.agenticchat.observability.TokenUsageDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns one model call into one Langfuse {@code generation} observation.
 *
 * <p>One instance per configured LLM role, exactly like the
 * {@code TokenCostListener} it sits beside: the role is the observation's name and a
 * filterable metadata key, so "the judge is most of the calls and a small part of the
 * cost" is a question the trace answers as well as the Prometheus counters do. Without
 * the role, a trace is a list of anonymous model calls and the two views cannot be
 * joined.
 *
 * <p><b>Nothing here throws into the call it observes.</b> LangChain4j's
 * {@code ChatModelListenerUtils} wraps every listener callback in
 * {@code catch (Exception e)} and logs one generic line that names neither this class
 * nor the role, so an uncaught exception is an invisible hole in the trace rather than
 * an error anybody investigates. Every callback below catches and logs with the role in
 * the message.
 *
 * <p><b>If neither {@code onResponse} nor {@code onError} ever fires</b> — a
 * possibility this class cannot rule out, since it does not own the call site — two
 * things happen and only the first is obvious. The span is never ended, so it is never
 * exported: the generation is missing from the trace, which is a hole rather than a
 * failed turn. Less obviously, {@code OtelAgentTracer.start} makes the span current on
 * the calling thread, so the scope stays attached to it; on a pooled request thread the
 * next observation started there becomes a child of a span that never ends, which
 * mis-parents every later trace on that thread rather than only this one. LangChain4j's
 * own {@code ChatModel.chat} pairs {@code onRequest} with exactly one of the two — the
 * {@code doChat} call sits inside a {@code try}/{@code catch} that calls {@code onError}
 * and rethrows — so this is a note about the contract, not a live defect.
 */
public class LangfuseChatModelListener implements ChatModelListener {

    private static final Logger LOG = LoggerFactory.getLogger(LangfuseChatModelListener.class);

    /**
     * Where the open observation waits between {@code onRequest} and its ending
     * callback.
     *
     * <p>The attribute map is shared by every listener attached to the model — the token
     * accounting keeps its start time in the same map — so the key is namespaced. It
     * also means two instances of THIS listener on one model would collide: the second
     * {@code onRequest} would overwrite the first's stash, leaving the first span
     * unclosed and its scope leaked on the request thread. One instance per role, which
     * is what the registry builds.
     */
    private static final String OBSERVATION_KEY = "agentic.observation.generation";

    /** Filterable in Langfuse only because {@code Observation.metadata} prefixes it. */
    private static final String ROLE = "role";

    /**
     * The response-side facts, written as metadata rather than as
     * {@code gen_ai.response.*}.
     *
     * <p>{@link Observation} exposes no raw attribute setter and its {@code genAi} method
     * writes the three request-side keys only, so metadata is the one route available —
     * and it is the better one for the question these actually get asked ("show me every
     * generation that stopped on LENGTH"), because an ordinary OpenTelemetry attribute
     * lands in Langfuse's non-filterable {@code metadata.attributes} catch-all. The
     * trade is real: {@code gen_ai.response.finish_reasons} and
     * {@code gen_ai.response.model} stay unset, so a Tempo or spanmetrics query does not
     * see them.
     */
    private static final String FINISH_REASON = "finish_reason";

    /** The build that actually served the call. Same route, same reason. */
    private static final String RESPONSE_MODEL = "response_model";

    private final String role;
    private final AgentTracer tracer;
    private final CostCalculator costs;
    private final ObservationContentPolicy content;

    public LangfuseChatModelListener(String role,
                                     AgentTracer tracer,
                                     CostCalculator costs,
                                     ObservationContentPolicy content) {
        this.role = role;
        this.tracer = tracer;
        this.costs = costs;
        this.content = content;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        try {
            var observation = tracer.start(role, ObservationType.GENERATION);
            // Stashed before a single attribute is written to it, so that a failure
            // while describing the request still leaves the ending callback something
            // to close. A span nobody closes is a span nobody exports.
            context.attributes().put(OBSERVATION_KEY, observation);
            describe(observation, context);
        } catch (RuntimeException e) {
            LOG.warn("Could not open the generation observation for role {}", role, e);
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        var observation = take(context.attributes());
        if (observation == null) {
            return;
        }
        try {
            record(observation, context);
        } catch (RuntimeException e) {
            LOG.warn("Could not describe the generation response for role {}", role, e);
        } finally {
            observation.close();
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        var observation = take(context.attributes());
        if (observation == null) {
            return;
        }
        try {
            observation.failed(context.error());
        } catch (RuntimeException e) {
            LOG.warn("Could not record the generation failure for role {}", role, e);
        } finally {
            // In the finally block and not after the try: the span has to end even when
            // describing the failure is what failed, or the error is invisible twice.
            observation.close();
        }
    }

    private void describe(Observation observation, ChatModelRequestContext context) {
        observation.metadata(ROLE, role);

        var request = context.chatRequest();
        if (request == null) {
            return;
        }
        String requestedModel = request.modelName();
        observation.model(requestedModel, parametersOf(request.parameters()));
        observation.genAi(systemOf(context.modelProvider()), GenAiAttributes.OPERATION_CHAT, requestedModel);
        // The system prompt is one of these messages and goes through the same policy as
        // the user's text: it is the largest single thing this application sends a
        // provider, and it is not automatically safe to forward to a third one.
        observation.input(content.capture(messagesOf(request.messages())));
    }

    private void record(Observation observation, ChatModelResponseContext context) {
        var response = context.chatResponse();
        if (response == null) {
            return;
        }
        observation.output(content.capture(outputOf(response.aiMessage())));
        observation.metadata(FINISH_REASON, response.finishReason() == null ? null : response.finishReason().name());
        observation.metadata(RESPONSE_MODEL, response.modelName());

        var usage = TokenUsageDetails.of(response.tokenUsage());
        observation.usage(usage);
        observation.cost(costs.breakdownOf(pricedModelOf(context), usage));
    }

    /**
     * Cost is priced by the model that was REQUESTED, never the one the response
     * reports having served.
     *
     * <p>{@code agentic.llm.pricing} is keyed by the configured model id — {@code
     * gpt-4o-mini}, {@code gemini-2-5-flash-lite} — which is what the request carries.
     * OpenAI answers with the dated build it actually served ({@code
     * gpt-4o-mini-2024-07-18}), which is not a key there, so pricing by the response name
     * yields no {@code cost_details} at all: silently, and only on that provider. The
     * name that served the call is not lost, it is recorded beside it as
     * {@link #RESPONSE_MODEL}.
     */
    private static String pricedModelOf(ChatModelResponseContext context) {
        var request = context.chatRequest();
        return request == null ? null : request.modelName();
    }

    /**
     * Removes rather than reads: the map outlives this callback, and leaving the
     * observation in it keeps a reference to an ended span for the life of the call.
     */
    private static Observation take(Map<Object, Object> attributes) {
        Object stashed = attributes == null ? null : attributes.remove(OBSERVATION_KEY);
        return stashed instanceof Observation observation ? observation : null;
    }

    /**
     * What the model was invoked with, in the {@code snake_case} the usage buckets and
     * Langfuse's own OpenAI-schema mapping already use — mixing the two spellings in one
     * trace makes a dashboard's group-by silently miss half its rows.
     */
    private static Map<String, Object> parametersOf(ChatRequestParameters parameters) {
        var values = new LinkedHashMap<String, Object>();
        if (parameters == null) {
            return values;
        }
        put(values, "temperature", parameters.temperature());
        put(values, "max_output_tokens", parameters.maxOutputTokens());
        put(values, "top_p", parameters.topP());
        put(values, "top_k", parameters.topK());
        put(values, "frequency_penalty", parameters.frequencyPenalty());
        put(values, "presence_penalty", parameters.presencePenalty());
        put(values, "stop_sequences", parameters.stopSequences());
        put(values, "tool_choice", parameters.toolChoice() == null ? null : parameters.toolChoice().name());
        put(values, "response_format", formatOf(parameters.responseFormat()));
        put(values, "tools", toolNamesOf(parameters.toolSpecifications()));
        return values;
    }

    /**
     * The type, never the JSON schema behind it. A structured-output schema is repeated
     * verbatim on every call that uses one, and it is a property of the code rather than
     * of the turn — the sort of constant that inflates every span and tells a reader
     * nothing they could not read in the source.
     */
    private static String formatOf(ResponseFormat format) {
        return format == null || format.type() == null ? null : format.type().name();
    }

    /**
     * Names only, for the same reason, and for one more: what changes between calls in
     * this application is WHICH tools the model could see, because a skill activation
     * makes a new set visible. The names carry that; the schemas would carry it too, at
     * several kilobytes per generation.
     */
    private static List<String> toolNamesOf(List<ToolSpecification> specifications) {
        return specifications == null
                ? List.of()
                : specifications.stream().map(ToolSpecification::name).toList();
    }

    /**
     * The messages as a role/content list, which is the shape Langfuse renders as a
     * conversation instead of as a blob of JSON.
     */
    private static List<Map<String, Object>> messagesOf(List<ChatMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        var written = new ArrayList<Map<String, Object>>(messages.size());
        for (ChatMessage message : messages) {
            written.add(messageOf(message));
        }
        return written;
    }

    private static Map<String, Object> messageOf(ChatMessage message) {
        var written = new LinkedHashMap<String, Object>();
        switch (message) {
            case SystemMessage system -> {
                written.put("role", "system");
                put(written, "content", system.text());
            }
            case UserMessage user -> {
                written.put("role", "user");
                put(written, "content", userContentOf(user));
            }
            case AiMessage ai -> {
                written.put("role", "assistant");
                put(written, "content", ai.text());
                put(written, "tool_calls", toolCallsOf(ai));
            }
            case ToolExecutionResultMessage result -> {
                written.put("role", "tool");
                put(written, "tool_call_id", result.id());
                put(written, "name", result.toolName());
                put(written, "content", result.text());
            }
            // A CustomMessage, or whatever a future version adds. Named by its own type
            // rather than dropped: an input that is missing one message reads as a
            // faithful transcript and is not one.
            default -> {
                written.put("role", message.type().name().toLowerCase(Locale.ROOT));
                put(written, "content", String.valueOf(message));
            }
        }
        return written;
    }

    /**
     * A non-text part is NAMED, never inlined. {@code ImageContent.toString} carries the
     * whole base64 data URL, which would put megabytes on a span attribute — and this
     * application sends text, so the branch exists to stay honest about a message it did
     * not expect rather than to render one.
     */
    private static Object userContentOf(UserMessage message) {
        if (message.hasSingleText()) {
            return message.singleText();
        }
        var parts = new ArrayList<String>();
        for (Content part : message.contents()) {
            parts.add(part instanceof TextContent text ? text.text() : "[" + part.type().name() + "]");
        }
        return String.join("\n", parts);
    }

    /**
     * The output of a turn where the model asked for tools instead of answering. Without
     * it that generation's output is empty in the UI and the trace shows the model
     * saying nothing, which is the opposite of what happened.
     */
    private static Object outputOf(AiMessage message) {
        if (message == null) {
            return null;
        }
        if (!message.hasToolExecutionRequests()) {
            return message.text();
        }
        var output = new LinkedHashMap<String, Object>();
        // Both, not either: a provider may return a sentence AND a tool call in the same
        // message, and writing only the calls would lose the sentence.
        put(output, "text", message.text());
        put(output, "tool_calls", toolCallsOf(message));
        return output;
    }

    private static List<Map<String, Object>> toolCallsOf(AiMessage message) {
        if (!message.hasToolExecutionRequests()) {
            return List.of();
        }
        var calls = new ArrayList<Map<String, Object>>();
        for (ToolExecutionRequest request : message.toolExecutionRequests()) {
            var call = new LinkedHashMap<String, Object>();
            put(call, "id", request.id());
            put(call, "name", request.name());
            // The arguments the model chose. They are model output and reach the tool
            // layer as untrusted input; the trace is where a bad call is diagnosed.
            put(call, "arguments", request.arguments());
            calls.add(call);
        }
        return calls;
    }

    /**
     * {@code gen_ai.system} from LangChain4j's own provider enum, lower-cased.
     *
     * <p>The enum is on the compile classpath and names which SDK actually made the call,
     * so this spelling is derived rather than remembered. The OpenTelemetry GenAI
     * registry publishes canonical values for a subset of these providers, but this file
     * does not quote that document, and a hand-written map to it would be a wire contract
     * reconstructed from recall — which is the one thing this repository's conventions
     * forbid.
     */
    private static String systemOf(ModelProvider provider) {
        return provider == null ? null : provider.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Absent and empty are the same claim about a model parameter, and writing both
     * shapes only makes two otherwise identical spans diff.
     */
    private static void put(Map<String, Object> values, String key, Object value) {
        if (value == null || (value instanceof Collection<?> collection && collection.isEmpty())) {
            return;
        }
        values.put(key, value);
    }
}
