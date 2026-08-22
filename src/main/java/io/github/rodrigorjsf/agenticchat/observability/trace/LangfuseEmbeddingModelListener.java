package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.embedding.listener.EmbeddingModelErrorContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import dev.langchain4j.model.embedding.listener.EmbeddingModelRequestContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelResponseContext;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import io.github.rodrigorjsf.agenticchat.observability.CostCalculator;
import io.github.rodrigorjsf.agenticchat.observability.TokenUsageDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One embedding call, observed as a Langfuse {@code embedding}.
 *
 * <p>{@code embedding} and {@code generation} are the only two observation types Langfuse
 * accepts usage and cost on, which is the whole reason this is not a plain {@code span}:
 * an embedding model that bills per token is an LLM line item, and typing it correctly is
 * what puts it in the same decomposition as the chat calls.
 *
 * <h2>What the local model does and does not report</h2>
 * <p>This application's embedding model is the quantized MiniLM running in-process on ONNX
 * Runtime ({@code EmbeddingModelFactory}), and the honest accounting for it is narrower
 * than "a local model reports nothing":
 *
 * <ul>
 *   <li><b>Input tokens are reported and are real.</b>
 *       {@code AbstractInProcessEmbeddingModel} returns
 *       {@code new TokenUsage(tokenCount - 2)} straight from the BERT tokenizer, minus the
 *       two special {@code [CLS]}/{@code [SEP]} tokens. So {@code usage_details} carries a
 *       measured count, not an estimate.</li>
 *   <li><b>There are no output tokens</b>, because an embedding has no completion. The
 *       bucket is absent rather than zero — {@link TokenUsageDetails} writes a bucket only
 *       when the provider reported one, and an absent bucket and a zero one are different
 *       claims.</li>
 *   <li><b>There is no cost</b>, because nothing is billed: no network call, no provider.
 *       {@link CostCalculator} returns an empty breakdown for a model with no entry in
 *       {@code agentic.llm.pricing}, so {@code cost_details} is absent by construction. A
 *       hosted embedding model configured later gets its cost with no change here, which
 *       is the reason cost is asked for at all rather than hard-coded to nothing.</li>
 * </ul>
 *
 * <p><b>The model name has to be supplied.</b> Every listener context hands back the
 * <em>listening wrapper</em> rather than the underlying model, and
 * {@code AllMiniLmL6V2QuantizedEmbeddingModel} overrides neither {@code modelName()} nor
 * {@code provider()}, so asking the context gives the interface defaults: the literal
 * string {@code "unknown"} and {@code ModelProvider.OTHER}. Writing {@code "unknown"} into
 * {@code langfuse.observation.model.name} is worse than writing nothing, because it looks
 * like data. The name is therefore a constructor argument, exactly as the role is for
 * {@link LangfuseChatModelListener}, and it is also the key {@link CostCalculator} prices
 * against.
 *
 * <h2>Nothing here throws into the call it observes</h2>
 * <p>{@code EmbeddingModelListenerUtils} wraps every callback in {@code catch (Exception)}
 * and logs one line that names neither this class nor the model, so an uncaught exception
 * is an invisible hole in the trace rather than an error anybody investigates.
 *
 * <p><b>On threading</b>, the batch path farms each segment out to a thread pool
 * ({@code AbstractInProcessEmbeddingModel.parallelizeEmbedding}) but blocks on
 * {@code future.get()} on the calling thread, and the listening wrapper calls
 * {@code onRequest} and its ending callback either side of that block. Both therefore run
 * on the thread that opened the observation, so the OpenTelemetry scope stays balanced —
 * worth stating, because a reader who sees the executor will assume otherwise.
 */
public class LangfuseEmbeddingModelListener implements EmbeddingModelListener {

    private static final Logger LOG = LoggerFactory.getLogger(LangfuseEmbeddingModelListener.class);

    /**
     * Where the open observation waits between {@code onRequest} and its ending callback.
     *
     * <p>Namespaced because the attributes map is shared by every listener on the model.
     * Two instances of THIS listener on one model would still collide — the second
     * {@code onRequest} would overwrite the first's stash, leaving that span unclosed and
     * its scope stranded on the request thread — so one instance per model.
     */
    private static final String OBSERVATION_KEY = "agentic.observation.embedding";

    /**
     * How many texts this call embedded. Filterable, and it is what separates the one
     * boot-time ingestion of the corpus from the single-query embeddings that follow.
     *
     * <p>It doubles as the key of the same count inside the batch input, on purpose: the
     * batch shape has to name the number it carries, and naming it anything else would put
     * two spellings of one quantity into the same span.
     */
    private static final String INPUTS = "inputs";

    private final String modelName;
    private final AgentTracer tracer;
    private final CostCalculator costs;
    private final ObservationContentPolicy content;

    public LangfuseEmbeddingModelListener(String modelName,
                                          AgentTracer tracer,
                                          CostCalculator costs,
                                          ObservationContentPolicy content) {
        this.modelName = modelName;
        this.tracer = tracer;
        this.costs = costs;
        this.content = content;
    }

    @Override
    public void onRequest(EmbeddingModelRequestContext context) {
        try {
            var observation = tracer.start(modelName, ObservationType.EMBEDDING);
            // Stashed before a single attribute is written to it, so that a failure while
            // describing the request still leaves the ending callback something to close.
            // A span nobody closes is a span nobody exports.
            context.attributes().put(OBSERVATION_KEY, observation);
            describe(observation, context);
        } catch (RuntimeException e) {
            LOG.warn("Could not open the embedding observation for model {}", modelName, e);
        }
    }

    @Override
    public void onResponse(EmbeddingModelResponseContext context) {
        var observation = take(context.attributes());
        if (observation == null) {
            return;
        }
        try {
            record(observation, context);
        } catch (RuntimeException e) {
            LOG.warn("Could not describe the embedding response for model {}", modelName, e);
        } finally {
            observation.close();
        }
    }

    @Override
    public void onError(EmbeddingModelErrorContext context) {
        var observation = take(context.attributes());
        if (observation == null) {
            return;
        }
        try {
            observation.failed(context.error());
        } catch (RuntimeException e) {
            LOG.warn("Could not record the embedding failure for model {}", modelName, e);
        } finally {
            // In the finally block and not after the try: the span has to end even when
            // describing the failure is what failed, or the error is invisible twice.
            observation.close();
        }
    }

    private void describe(Observation observation, EmbeddingModelRequestContext context) {
        observation.model(modelName, parametersOf(context.embeddingRequest()));
        observation.genAi(systemOf(context.modelProvider()), GenAiAttributes.OPERATION_EMBEDDINGS, modelName);

        List<TextSegment> segments = context.textSegments();
        observation.metadata(INPUTS, segments.size());
        observation.input(content.capture(inputOf(segments)));
    }

    private void record(Observation observation, EmbeddingModelResponseContext context) {
        // context.response() rather than context.embeddingResponse(): the context's own
        // constructor requires this one to be non-null and lets the other be absent, and on
        // the convenience paths the wrapper rebuilds the response metadata without a model
        // name. This field carries the embeddings and the usage on every path.
        var response = context.response();
        if (response == null) {
            return;
        }
        List<Embedding> embeddings = response.content();
        // Two integers derived from the call, so not subject to the content policy: with
        // capture off a trace still shows that an embedding happened and how big it was.
        // The vectors themselves are never written — 384 floats no reader interprets.
        observation.output(outputOf(embeddings));

        var usage = TokenUsageDetails.of(response.tokenUsage());
        observation.usage(usage);
        observation.cost(costs.breakdownOf(modelName, usage));
    }

    /**
     * A single input is written as its text; a batch is written as its shape.
     *
     * <p>The one text is the user's question on its way to the vector store, which is the
     * whole point of looking at an embedding observation. A batch, in this application, is
     * only ever {@code KnowledgeBase} ingesting the corpus at boot: several dozen segments
     * of a few hundred characters each, whose content is a set of files in this repository.
     * Copying that onto a span attribute on every start costs tens of kilobytes per boot
     * and tells a reader nothing they could not read in {@code src/main/resources/knowledge}.
     * The count and the character total keep the batch visible without carrying it.
     */
    private static Object inputOf(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        if (segments.size() == 1) {
            return segments.getFirst().text();
        }
        long characters = 0;
        for (TextSegment segment : segments) {
            characters += segment.text() == null ? 0 : segment.text().length();
        }
        var shape = new LinkedHashMap<String, Object>();
        shape.put(INPUTS, segments.size());
        shape.put("characters", characters);
        return shape;
    }

    /**
     * The number of vectors and their dimension — never the vectors.
     *
     * <p>384 floats per segment is what this model produces, so the boot-time ingestion
     * alone would write tens of thousands of numbers into one span attribute. They are also
     * unreadable to the person looking at the trace, undiffable between runs, and
     * reproducible from the input by re-running the model, so the payload buys nothing at
     * any size. What a reader actually checks — that the call returned as many vectors as it
     * was given texts, in the dimension the store was built for — is these two numbers.
     *
     * <p>The dimension is read off the returned embedding rather than from
     * {@code EmbeddingModel.dimension()}: that method's default implementation embeds the
     * word "test" when the model does not publish a known dimension, which from inside
     * {@code onResponse} would call this listener again. MiniLM happens to publish 384 and
     * would terminate; the listener must not depend on it.
     *
     * <p>This output is not passed through {@link ObservationContentPolicy}, because it
     * carries no content — two integers derived from a call, not anything anybody wrote.
     */
    private static Object outputOf(List<Embedding> embeddings) {
        var output = new LinkedHashMap<String, Object>();
        output.put("embeddings", embeddings == null ? 0 : embeddings.size());
        if (embeddings != null && !embeddings.isEmpty() && embeddings.getFirst() != null) {
            output.put("dimension", embeddings.getFirst().dimension());
        }
        return output;
    }

    /**
     * The per-call parameters, in the {@code snake_case} the usage buckets and the chat
     * observation already use — mixing spellings in one trace makes a dashboard's group-by
     * silently miss half its rows. Both are absent for the in-process model, which supports
     * no per-call parameters at all, so this normally writes nothing.
     */
    private static Map<String, Object> parametersOf(EmbeddingRequest request) {
        var values = new LinkedHashMap<String, Object>();
        if (request == null) {
            return values;
        }
        if (request.dimensions() != null) {
            values.put("dimensions", request.dimensions());
        }
        if (request.inputType() != null) {
            values.put("input_type", request.inputType().name());
        }
        return values;
    }

    /**
     * Removes rather than reads: the map outlives this callback, and leaving the observation
     * in it keeps a reference to an ended span for the life of the call.
     */
    private static Observation take(Map<Object, Object> attributes) {
        Object stashed = attributes == null ? null : attributes.remove(OBSERVATION_KEY);
        return stashed instanceof Observation observation ? observation : null;
    }

    /**
     * {@code gen_ai.system} from LangChain4j's own provider enum, lower-cased — derived
     * rather than remembered, for the reason {@link LangfuseChatModelListener} gives. For
     * the in-process model this is {@code "other"}, which is what the enum says and is
     * therefore the true answer rather than a placeholder.
     */
    private static String systemOf(ModelProvider provider) {
        return provider == null ? null : provider.name().toLowerCase(Locale.ROOT);
    }
}
