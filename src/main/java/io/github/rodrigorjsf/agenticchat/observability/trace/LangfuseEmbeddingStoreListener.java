package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreErrorContext;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreListener;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreRequestContext;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreResponseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One vector-store search, observed as a Langfuse {@code retriever}.
 *
 * <h2>Why this exists beside {@link LangfuseRetrieverListener}</h2>
 * <p>It would be redundant if it carried the same facts one level down, and most of what
 * the store sees the retriever observation already has: the scores, the segments and their
 * ids are identical on both sides of {@code EmbeddingStoreContentRetriever.retrieve}, which
 * does nothing to the matches but copy them into {@code Content} objects.
 *
 * <p>One thing is visible here and nowhere else: <b>the threshold and the result cap
 * actually in force for this search</b>. {@code EmbeddingStoreContentRetriever} resolves
 * {@code minScore} and {@code maxResults} per query — they are {@code Function<Query, …>}
 * fields, so they may differ from the configured constants — and then keeps them to itself:
 * the class publishes no getter for either (its public surface is four constructors,
 * {@code from}, {@code retrieve} and {@code toString}), and the retriever listener's context
 * hands back the listening wrapper rather than the retriever. The only place those two
 * numbers surface is the {@link EmbeddingSearchRequest} this listener is handed.
 *
 * <p>That pairing is what a threshold argument needs. Scores alone say what came back;
 * scores beside the threshold that produced them say whether a different threshold would
 * have produced something else. Its limit is worth stating too: the store applies
 * {@code minScore} itself, so a segment that scored 0.71 against a threshold of 0.72 is
 * discarded inside {@code search} and appears in no observation anywhere. What this
 * observation establishes is "0.72 was in force and nothing cleared it" — not what the near
 * miss was.
 *
 * <h2>Only a search is a retrieval</h2>
 * <p>The same listener SPI fires for {@code add}, {@code addAll}, {@code remove} and
 * {@code removeAll}. {@code KnowledgeBase} calls {@code addAll} once at boot with the whole
 * corpus, so a listener that observed every operation would put a {@code retriever}
 * observation on an ingestion write on every start — and {@link ObservationType#RETRIEVER}
 * is defined as a lookup that reads and does not change state. Anything that is not a search
 * is ignored, and because the ending callbacks key off the stash rather than off the context
 * type, ignoring it in {@code onRequest} is enough.
 *
 * <p><b>Nothing here throws into the call it observes.</b>
 * {@code EmbeddingStoreListenerUtils} catches every callback and logs one generic line that
 * names neither this class nor the store, so an uncaught exception is an invisible hole in
 * the trace rather than an error anybody investigates.
 */
public class LangfuseEmbeddingStoreListener implements EmbeddingStoreListener {

    private static final Logger LOG = LoggerFactory.getLogger(LangfuseEmbeddingStoreListener.class);

    /**
     * A constant rather than a constructor argument, unlike the two sibling listeners.
     * A store has no name to be given: this observation is always the search underneath a
     * retriever observation that is already named, and its own name says which step of that
     * retrieval it is.
     */
    static final String OBSERVATION_NAME = "embedding-store-search";

    /**
     * Where the open observation waits between {@code onRequest} and its ending callback.
     * Its presence is also the signal that this operation was a search: nothing else stashes,
     * so nothing else is ever closed.
     */
    private static final String OBSERVATION_KEY = "agentic.observation.store-search";

    /** Filterable, so "every search that ran at 0.72" is a query rather than a deployment archaeology exercise. */
    private static final String MIN_SCORE = "min_score";

    private static final String MAX_RESULTS = "max_results";
    private static final String MATCHES = "matches";

    private final AgentTracer tracer;

    public LangfuseEmbeddingStoreListener(AgentTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void onRequest(EmbeddingStoreRequestContext<?> context) {
        if (!(context instanceof EmbeddingStoreRequestContext.Search<?> search)) {
            return;
        }
        try {
            var observation = tracer.start(OBSERVATION_NAME, ObservationType.RETRIEVER);
            // Stashed before anything is written to it, so a failure while describing the
            // search still leaves the ending callback something to close.
            context.attributes().put(OBSERVATION_KEY, observation);
            describe(observation, search.searchRequest());
        } catch (RuntimeException e) {
            LOG.warn("Could not open the embedding-store search observation", e);
        }
    }

    @Override
    public void onResponse(EmbeddingStoreResponseContext<?> context) {
        var observation = take(context.attributes());
        if (observation == null) {
            return;
        }
        try {
            if (context instanceof EmbeddingStoreResponseContext.Search<?> search) {
                var result = search.searchResult();
                observation.output(outputOf(result == null ? null : result.matches()));
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not describe the embedding-store search result", e);
        } finally {
            observation.close();
        }
    }

    @Override
    public void onError(EmbeddingStoreErrorContext<?> context) {
        var observation = take(context.attributes());
        if (observation == null) {
            return;
        }
        try {
            observation.failed(context.error());
        } catch (RuntimeException e) {
            LOG.warn("Could not record the embedding-store search failure", e);
        } finally {
            // In the finally block and not after the try: the span has to end even when
            // describing the failure is what failed.
            observation.close();
        }
    }

    /**
     * The search parameters are this observation's input, which is a deliberate departure
     * from writing the query there.
     *
     * <p>The query text is already the input of the retriever observation this one nests
     * inside, one level up and a few microseconds earlier. Writing it again would duplicate
     * the user's own words on a second span — payload, and a second copy of personal data,
     * for nothing. The parameters are what this span uniquely knows, so they take the field
     * that gets read.
     */
    private static void describe(Observation observation, EmbeddingSearchRequest request) {
        if (request == null) {
            return;
        }
        observation.metadata(MIN_SCORE, request.minScore());
        observation.metadata(MAX_RESULTS, request.maxResults());

        var input = new LinkedHashMap<String, Object>();
        input.put(MIN_SCORE, request.minScore());
        input.put(MAX_RESULTS, request.maxResults());
        if (request.queryEmbedding() != null) {
            // The dimension, never the query vector: 384 floats that no reader interprets and
            // that the embedding observation's own output already reports the size of. The
            // number is here because a store built at one dimension and queried at another is
            // a real failure whose only symptom is bad scores.
            input.put("dimensions", request.queryEmbedding().dimension());
        }
        if (request.filter() != null) {
            input.put("filter", String.valueOf(request.filter()));
        }
        observation.input(input);
    }

    /**
     * How many matches survived the threshold, and the range they scored in.
     *
     * <p>Deliberately not the matches themselves. This span is a child of the retriever
     * observation, which already writes a score, a source and an id per segment; repeating
     * them here would double the payload of every retrieving turn to say the same thing
     * twice. The range is two numbers and it is the pair that makes the threshold beside it
     * mean something.
     */
    private static Object outputOf(List<? extends EmbeddingMatch<?>> matches) {
        var output = new LinkedHashMap<String, Object>();
        output.put(MATCHES, matches == null ? 0 : matches.size());
        Double top = null;
        Double lowest = null;
        if (matches != null) {
            for (EmbeddingMatch<?> match : matches) {
                if (match == null || match.score() == null) {
                    continue;
                }
                double score = match.score();
                top = top == null || score > top ? score : top;
                lowest = lowest == null || score < lowest ? score : lowest;
            }
        }
        if (top != null) {
            output.put("top_score", top);
            output.put("lowest_score", lowest);
        }
        return output;
    }

    /**
     * Removes rather than reads: leaving the observation in the map keeps a reference to an
     * ended span for the life of the call.
     */
    private static Observation take(Map<Object, Object> attributes) {
        Object stashed = attributes == null ? null : attributes.remove(OBSERVATION_KEY);
        return stashed instanceof Observation observation ? observation : null;
    }
}
