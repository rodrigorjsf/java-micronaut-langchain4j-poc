package io.github.rodrigorjsf.agenticchat.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import io.github.rodrigorjsf.agenticchat.observability.CostCalculator;
import io.github.rodrigorjsf.agenticchat.observability.trace.AgentTracer;
import io.github.rodrigorjsf.agenticchat.observability.trace.LangfuseEmbeddingModelListener;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationContentPolicy;
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

    /**
     * The model name is a literal here and nowhere else. There is no provider to ask: the
     * ONNX model is loaded from the jar, so nothing at runtime knows what it is called, and
     * a trace that says only "an embedding happened" cannot be compared against the day
     * this project swaps the model.
     */
    static final String MODEL_NAME = "all-minilm-l6-v2-q";

    /**
     * {@code addListener} returns a DECORATOR — the model is wrapped, not mutated — so the
     * returned instance is the one that must be published as the bean.
     */
    @Singleton
    EmbeddingModel embeddingModel(AgentTracer tracer, CostCalculator costs, ObservationContentPolicy content) {
        return new AllMiniLmL6V2QuantizedEmbeddingModel()
                .addListener(new LangfuseEmbeddingModelListener(MODEL_NAME, tracer, costs, content));
    }
}
