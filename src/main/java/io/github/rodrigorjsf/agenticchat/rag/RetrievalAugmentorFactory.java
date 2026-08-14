package io.github.rodrigorjsf.agenticchat.rag;

import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/**
 * The retrieval pipeline: route, then retrieve, then inject.
 *
 * <p>Only the router is customised. The default aggregator and injector are
 * adequate here and replacing them would be decoration — a re-ranking aggregator
 * in particular would cost a second model and would also discard the relevance
 * scores that make citation possible.
 */
@Factory
public class RetrievalAugmentorFactory {

    @Singleton
    RetrievalAugmentor retrievalAugmentor(SkillAwareQueryRouter router) {
        return DefaultRetrievalAugmentor.builder()
                .queryRouter(router)
                .build();
    }
}
