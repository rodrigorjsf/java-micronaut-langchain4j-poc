package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverErrorContext;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverListener;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverRequestContext;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverResponseContext;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One retrieval, observed as a Langfuse {@code retriever}.
 *
 * <p>The type is load-bearing: Langfuse draws an agent graph for a trace only when it holds
 * an observation typed something other than {@code span}, {@code event} or
 * {@code generation}, so this and the {@code tool} and {@code guardrail} observations
 * around it are what turn a flat list of spans into the picture of a turn.
 *
 * <h2>The scores are the point</h2>
 * <p>Retrieval in this application is gated twice, and each gate is an argument someone
 * will want to reopen with evidence. {@code SkillAwareQueryRouter} decides whether
 * retrieval runs at all — when the triage verdict named a skill it returns no retriever and
 * this listener never fires, so <b>a turn with no retriever observation is a routed-away
 * turn, not a failed one</b>. When it does run, a relevance threshold of 0.72 decides what
 * survives; that number came from a measurement on this corpus, recorded in
 * {@code application.yml}, in which relevant questions scored 0.7299-0.8475 and irrelevant
 * ones 0.6826-0.7342 — <em>overlapping</em> distributions, which is why the router exists at
 * all.
 *
 * <p>An argument about where that threshold belongs is settled with scores from real
 * traffic, and this observation is the only place they are ever written down. That is why
 * the output carries a score per segment rather than a count: the count answers "did
 * retrieval find anything", the scores answer "was it right to".
 *
 * <h2>Identifiers, not the retrieved text</h2>
 * <p>The segments themselves are already in the trace. The content injector splices them
 * into the user message, and the very next {@code generation} observation writes that
 * message as its input, verbatim. Repeating them here would double the payload of every
 * retrieving turn for bytes a reader can already see one span away — so this observation
 * carries what that one does not: the score, the source document, the store's id for the
 * segment and its length.
 *
 * <h2>Nesting, and the one shape that breaks it</h2>
 * <p>The observation opens in {@code onRequest} and closes in the ending callback, so the
 * embedding of the query and the vector-store search both happen inside it and become its
 * children. That holds because {@code DefaultRetrievalAugmentor.process} runs
 * {@code retrieve} INLINE on the calling thread when there is exactly one query and exactly
 * one retriever, which is this application's shape: the default query transformer produces
 * one query and {@code SkillAwareQueryRouter} returns at most one retriever. With two of
 * either, the augmentor switches to {@code supplyAsync(…, executor)} with no context
 * propagation, and this observation becomes a root span in a trace of its own rather than a
 * child of the turn. A note about the contract, not a live defect — but the day a second
 * retriever is added, this is where the trace comes apart.
 *
 * <p><b>Nothing here throws into the call it observes.</b>
 * {@code ContentRetrieverListenerUtils} catches every callback and logs one generic line
 * that names neither this class nor the query, so an uncaught exception is an invisible
 * hole rather than an error anybody investigates.
 *
 * <p><b>No {@code gen_ai.*} attributes</b>, unlike the generation and embedding
 * observations. The OpenTelemetry GenAI conventions publish no operation name for
 * retrieval, and inventing one would put a value nobody's dashboard groups by into a
 * namespace whose whole point is that everybody agrees on it.
 */
public class LangfuseRetrieverListener implements ContentRetrieverListener {

    private static final Logger LOG = LoggerFactory.getLogger(LangfuseRetrieverListener.class);

    /**
     * Where the open observation waits between {@code onRequest} and its ending callback.
     * The map is per call, created by the listening wrapper, but the key is still namespaced
     * because it is shared with every other listener on the same retriever.
     */
    private static final String OBSERVATION_KEY = "agentic.observation.retrieval";

    /**
     * Filterable, so "which turns retrieved nothing" is a query rather than a scan.
     *
     * <p>The same number is deliberately written into the output as well. Metadata is what
     * Langfuse filters on and the output is what a person reads, and a reader who opens one
     * observation should not have to look at a second field to learn how many segments the
     * list under their cursor holds.
     */
    private static final String SEGMENTS = "segments";

    /**
     * The best relevance score this query reached, filterable beside the segment count.
     * With a threshold in force the top score of an empty result is unknowable — the store
     * discards what it rejects — so this is absent when nothing came back, which is itself
     * the answer to "did anything come close".
     */
    private static final String TOP_SCORE = "top_score";

    private final String name;
    private final AgentTracer tracer;
    private final ObservationContentPolicy content;

    /**
     * @param name the observation's name in the UI. Supplied rather than read from the
     * retriever: every context hands back the listening wrapper, whose {@code toString} is
     * its own class, so {@code EmbeddingStoreContentRetriever}'s {@code displayName} —
     * {@code assistant-knowledge} here — is not reachable from inside a callback.
     */
    public LangfuseRetrieverListener(String name, AgentTracer tracer, ObservationContentPolicy content) {
        this.name = name;
        this.tracer = tracer;
        this.content = content;
    }

    @Override
    public void onRequest(ContentRetrieverRequestContext context) {
        try {
            var observation = tracer.start(name, ObservationType.RETRIEVER);
            // Stashed before anything is written to it: a failure while describing the query
            // must still leave the ending callback something to close, or the span is never
            // ended and therefore never exported.
            context.attributes().put(OBSERVATION_KEY, observation);
            observation.input(content.capture(queryTextOf(context.query())));
        } catch (RuntimeException e) {
            LOG.warn("Could not open the retrieval observation for retriever {}", name, e);
        }
    }

    @Override
    public void onResponse(ContentRetrieverResponseContext context) {
        var observation = take(context.attributes());
        if (observation == null) {
            return;
        }
        try {
            record(observation, context.contents());
        } catch (RuntimeException e) {
            LOG.warn("Could not describe the retrieval result for retriever {}", name, e);
        } finally {
            observation.close();
        }
    }

    @Override
    public void onError(ContentRetrieverErrorContext context) {
        var observation = take(context.attributes());
        if (observation == null) {
            return;
        }
        try {
            observation.failed(context.error());
        } catch (RuntimeException e) {
            LOG.warn("Could not record the retrieval failure for retriever {}", name, e);
        } finally {
            // In the finally block and not after the try: the span has to end even when
            // describing the failure is what failed.
            observation.close();
        }
    }

    private void record(Observation observation, List<Content> contents) {
        List<Map<String, Object>> results = resultsOf(contents);
        observation.metadata(SEGMENTS, results.size());
        observation.metadata(TOP_SCORE, topScoreOf(results));

        var output = new LinkedHashMap<String, Object>();
        output.put(SEGMENTS, results.size());
        output.put("results", results);
        // Deliberately NOT passed through ObservationContentPolicy: every field here is a
        // score, an identifier or a length, and none of it is the retrieved text. A
        // deployment that turns capture off still has to be able to argue about its own
        // threshold, and the scores are the only thing that argument is made of.
        // Retrieving nothing is not an error either, so an empty result is an empty list
        // rather than a WARNING — the threshold doing its job looks exactly like this.
        observation.output(output);
    }

    private static List<Map<String, Object>> resultsOf(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        var results = new ArrayList<Map<String, Object>>(contents.size());
        for (Content retrieved : contents) {
            if (retrieved == null) {
                continue;
            }
            Map<ContentMetadata, Object> metadata = retrieved.metadata();
            var result = new LinkedHashMap<String, Object>();
            // Retrieval order is score order, and it is preserved: which segment the model
            // saw first is part of what a retrieval argument is about.
            put(result, "score", valueOf(metadata, ContentMetadata.SCORE));
            put(result, "source", sourceOf(retrieved.textSegment()));
            put(result, "embedding_id", valueOf(metadata, ContentMetadata.EMBEDDING_ID));
            put(result, "characters", lengthOf(retrieved.textSegment()));
            results.add(result);
        }
        return results;
    }

    /**
     * A retriever that does not score its results carries no metadata at all, so this reads
     * the map defensively rather than assuming the shape
     * {@code EmbeddingStoreContentRetriever} happens to produce.
     */
    private static Object valueOf(Map<ContentMetadata, Object> metadata, ContentMetadata key) {
        return metadata == null ? null : metadata.get(key);
    }

    /**
     * The document a segment came from, which {@code KnowledgeBase} puts in metadata
     * precisely so a retrieved segment can be attributed back to a file. Absent metadata is
     * left absent rather than filled with a placeholder — a corpus built elsewhere may not
     * carry the key, and inventing one would make the trace claim knowledge it does not have.
     */
    private static String sourceOf(TextSegment segment) {
        return segment == null || segment.metadata() == null ? null : segment.metadata().getString("source");
    }

    private static Integer lengthOf(TextSegment segment) {
        return segment == null || segment.text() == null ? null : segment.text().length();
    }

    /**
     * @return the highest score present, or {@code null} when no result carried one — which
     * is what a retriever that does not score its results looks like, and is different from
     * a score of zero
     */
    private static Double topScoreOf(List<Map<String, Object>> results) {
        Double top = null;
        for (Map<String, Object> result : results) {
            if (result.get("score") instanceof Number score
                    && (top == null || score.doubleValue() > top)) {
                top = score.doubleValue();
            }
        }
        return top;
    }

    private static String queryTextOf(Query query) {
        return query == null ? null : query.text();
    }

    /**
     * Removes rather than reads: leaving the observation in the map keeps a reference to an
     * ended span for the life of the call.
     */
    private static Observation take(Map<Object, Object> attributes) {
        Object stashed = attributes == null ? null : attributes.remove(OBSERVATION_KEY);
        return stashed instanceof Observation observation ? observation : null;
    }

    /**
     * Absent and null are the same claim about a result field, and writing both shapes only
     * makes two otherwise identical spans diff.
     */
    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }
}
