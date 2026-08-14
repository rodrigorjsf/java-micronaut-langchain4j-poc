package io.github.rodrigorjsf.agenticchat.rag;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
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

    @Singleton
    ContentRetriever knowledgeRetriever(
            KnowledgeBase knowledge,
            @Value("${agentic.rag.max-results:3}") int maxResults,
            @Value("${agentic.rag.min-score:0.6}") double minScore) {

        return EmbeddingStoreContentRetriever.builder()
                .displayName("assistant-knowledge")
                .embeddingStore(knowledge.store())
                .embeddingModel(knowledge.embeddingModel())
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
    }
}
