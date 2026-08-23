package io.github.rodrigorjsf.agenticchat.rag;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import io.github.rodrigorjsf.agenticchat.observability.trace.AgentTracer;
import io.github.rodrigorjsf.agenticchat.observability.trace.LangfuseEmbeddingStoreListener;
import io.github.rodrigorjsf.agenticchat.observability.trace.LangfuseRetrieverListener;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationContentPolicy;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

/**
 * Retrieval over the assistant's own documentation.
 *
 * <p><b>No query router, on purpose.</b> The obvious refinement is to decide per
 * turn whether retrieval is worth doing. Here it is not worth building: the
 * embedding model runs in-process at roughly 14 ms and costs nothing, so routing
 * would save 14 ms and add a component that can be wrong. A {@code minScore}
 * threshold does the same job with no moving parts — an unrelated question simply
 * retrieves nothing and the model never sees a distracting segment.
 *
 * <p>A {@code LanguageModelQueryRouter} would be strictly worse than either, since
 * it spends an LLM call to avoid work that is already free.
 *
 * <p>{@code minScore} is a relevance score in [0,1], not raw cosine similarity.
 * 0.6 is tuned to let capability questions through while keeping a CEP lookup —
 * which a tool should answer — from dragging in prose about what a CEP is.
 */
@Factory
public class KnowledgeRetrieverFactory {

    static final String RETRIEVER_NAME = "assistant-knowledge";

    /**
     * Two listeners rather than one, and they are not redundant. The retriever observation
     * covers query in, segments and their scores out — which is the pair a threshold
     * argument is settled with. The store observation sits inside it and covers the vector
     * search alone, so the ~14 ms of embedding the query is separable from the search it
     * pays for.
     *
     * <p>{@code addListener} returns a decorator in both cases: the wrapped instance is the
     * one that has to be used, not the one it was called on.
     */
    @Singleton
    ContentRetriever knowledgeRetriever(
            KnowledgeBase knowledge,
            AgentTracer tracer,
            ObservationContentPolicy content,
            @Value("${agentic.rag.max-results:3}") int maxResults,
            @Value("${agentic.rag.min-score:0.6}") double minScore) {

        return EmbeddingStoreContentRetriever.builder()
                .displayName(RETRIEVER_NAME)
                .embeddingStore(knowledge.store()
                        .addListener(new LangfuseEmbeddingStoreListener(tracer)))
                .embeddingModel(knowledge.embeddingModel())
                .maxResults(maxResults)
                .minScore(minScore)
                .build()
                .addListener(new LangfuseRetrieverListener(RETRIEVER_NAME, tracer, content));
    }
}
