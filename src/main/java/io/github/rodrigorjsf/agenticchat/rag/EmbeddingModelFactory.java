package io.github.rodrigorjsf.agenticchat.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/**
 * The embedding model, in-process.
 *
 * <p>Quantized MiniLM on ONNX Runtime: 384 dimensions, ~5.7 s to load and ~14 ms
 * per embedding on this machine, about 18 MB of heap. No container, no GPU, no
 * network, no per-call cost — which is what makes retrieval affordable on a
 * machine with 7 GB of RAM shared with Docker
 * ({@code docs/adr/0003-no-local-model.md}).
 *
 * <p>Thread-safe and shared. The 5.7 s is paid once at startup because
 * {@link KnowledgeBase} is eager.
 */
@Factory
public class EmbeddingModelFactory {

    @Singleton
    EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2QuantizedEmbeddingModel();
    }
}
