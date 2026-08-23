package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.listener.EmbeddingModelErrorContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelRequestContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelResponseContext;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.github.rodrigorjsf.agenticchat.observability.CostCalculator;
import io.github.rodrigorjsf.agenticchat.observability.ModelPrice;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The embedding observation, asserted through exported spans.
 *
 * <p>Hand-built contexts rather than the real quantized MiniLM. The model takes about
 * 5.7 s to load and would put that on this suite for facts it cannot make any clearer:
 * what is under test is the mapping from a listener context to Langfuse's fields, and a
 * hand-built context states the token counts and the vector dimensions the assertions are
 * about instead of hoping a real call produces them. {@code RetrievalObservationTest}
 * covers the wiring end to end with a real store.
 */
class LangfuseEmbeddingModelListenerTest {

    /**
     * The name has to be supplied to the listener because nothing can be asked for it: the
     * context hands back the listening wrapper, and the in-process model overrides neither
     * {@code modelName()} nor {@code provider()}.
     */
    private static final String MODEL = "all-minilm-l6-v2-q";

    /**
     * An {@link EmbeddingModel} with nothing overridden — every method on that interface has
     * a default, so this compiles, and it is exactly what the production model looks like
     * from inside a callback: {@code modelName()} answers the literal string {@code
     * "unknown"} and {@code provider()} answers {@code OTHER}. The contexts require an
     * instance; the listener asks it nothing.
     */
    private static final EmbeddingModel UNNAMED_MODEL = new EmbeddingModel() {
    };

    private InMemorySpanExporter exported;
    private SdkTracerProvider tracerProvider;
    private AgentTracer tracer;

    @BeforeEach
    void setUp() {
        exported = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exported))
                .build();
        tracer = new OtelAgentTracer(tracerProvider.get("test"), ObservationJson.compact());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    @DisplayName("an embedding call is one embedding observation named after the model")
    void anEmbeddingCallIsOneEmbeddingObservation() {
        embed(listener(unpriced(), true), segments("o que voce consegue fazer?"), vectors(1));

        var span = onlySpan();
        assertThat(span.getName()).isEqualTo(MODEL);
        assertThat(span.getAttributes().asMap())
                // Not "span": embedding is one of only two types Langfuse accepts usage and
                // cost on, and it is also one of the types that make it draw an agent graph.
                .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "embedding")
                .containsEntry(LangfuseAttributes.MODEL_NAME, MODEL)
                .containsEntry(GenAiAttributes.OPERATION_NAME, "embeddings")
                .containsEntry(GenAiAttributes.REQUEST_MODEL, MODEL)
                // What LangChain4j's own enum says about an in-process model, rather than a
                // provider name invented for the trace.
                .containsEntry(GenAiAttributes.SYSTEM, "other");
    }

    @Test
    @DisplayName("the input tokens the in-process model reports become usage buckets")
    void theTokensTheLocalModelReportsAreRecorded() {
        // AbstractInProcessEmbeddingModel returns a real count from the BERT tokenizer, less
        // the two special tokens. There is no output bucket because an embedding has no
        // completion — absent, which is a different claim from zero.
        embed(listener(unpriced(), true), segments("previsao do tempo"),
                Response.from(vectorList(1), new TokenUsage(86)));

        assertThat(onlySpan().getAttributes().get(LangfuseAttributes.USAGE_DETAILS))
                .contains("\"input\":86", "\"total\":86")
                .doesNotContain("\"output\"");
    }

    @Test
    @DisplayName("a model with no configured price writes no cost details at all")
    void anUnpricedModelWritesNoCost() {
        embed(listener(unpriced(), true), segments("previsao do tempo"),
                Response.from(vectorList(1), new TokenUsage(86)));

        // Absent rather than zero. Nothing is billed for a model that runs in this process,
        // and a confident zero would be indistinguishable from a pricing table that forgot it.
        assertThat(onlySpan().getAttributes().get(LangfuseAttributes.COST_DETAILS)).isNull();
    }

    @Test
    @DisplayName("a priced embedding model has its cost ingested rather than inferred by Langfuse")
    void aPricedModelHasItsCostIngested() {
        var priced = new CostCalculator(List.of(new ModelPrice(
                MODEL, new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO)));

        embed(listener(priced, true), segments("previsao do tempo"),
                Response.from(vectorList(1), new TokenUsage(100)));

        // 10.00 USD per million input tokens, 100 tokens. The number is ingested so that the
        // trace and agentic.llm.pricing cannot disagree about the same call.
        assertThat(onlySpan().getAttributes().get(LangfuseAttributes.COST_DETAILS))
                .contains("\"input\":0.001", "\"total\":0.001");
    }

    @Test
    @DisplayName("the output is the number of embeddings and their dimension, never the vectors")
    void theVectorsAreNotWritten() {
        embed(listener(unpriced(), true), segments("um", "dois"), vectors(2));

        var attributes = onlySpan().getAttributes();
        assertThat(attributes.get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .isEqualTo("{\"embeddings\":2,\"dimension\":3}");
        // 384 floats per segment is unreadable, undiffable and reproducible from the input.
        // The dimension is read off the returned embedding, never from EmbeddingModel
        // .dimension(), whose default implementation embeds a word and would re-enter here.
        assertThat(attributes.get(LangfuseAttributes.OBSERVATION_OUTPUT)).doesNotContain("0.5");
    }

    @Test
    @DisplayName("one input is written as its text and a batch as its shape")
    void oneInputIsTextAndABatchIsAShape() {
        embed(listener(unpriced(), true), segments("bom dia"), vectors(1));
        assertThat(onlySpan().getAttributes().get(LangfuseAttributes.OBSERVATION_INPUT))
                .isEqualTo("\"bom dia\"");

        exported.reset();

        // The only batch this application makes is KnowledgeBase ingesting the corpus at
        // boot: files that are in the repository already, and tens of kilobytes on a span.
        embed(listener(unpriced(), true), segments("abc", "defg"), vectors(2));
        assertThat(onlySpan().getAttributes().get(LangfuseAttributes.OBSERVATION_INPUT))
                .contains("\"inputs\":2", "\"characters\":7")
                .doesNotContain("abc", "defg");
    }

    @Test
    @DisplayName("with content capture off the text is withheld but the shape of the call is not")
    void contentCaptureOffStillLeavesTheCallVisible() {
        embed(listener(unpriced(), false), segments("bom dia"), vectors(1));

        var attributes = onlySpan().getAttributes();
        assertThat(attributes.get(LangfuseAttributes.OBSERVATION_INPUT)).isNull();
        // The output carries no content — two integers derived from the call — so it is not
        // subject to the policy, and a trace with capture off still shows that an embedding
        // happened and how big it was.
        assertThat(attributes.get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .isEqualTo("{\"embeddings\":1,\"dimension\":3}");
        assertThat(attributes.get(LangfuseAttributes.MODEL_NAME)).isEqualTo(MODEL);
    }

    @Test
    @DisplayName("a failed embedding is recorded at ERROR level and the observation still closes")
    void aFailureIsRecordedAndTheSpanIsStillEnded() {
        var listener = listener(unpriced(), true);
        var attributes = new ConcurrentHashMap<Object, Object>();
        var segments = segments("previsao do tempo");

        listener.onRequest(EmbeddingModelRequestContext.builder()
                .textSegments(segments)
                .embeddingModel(UNNAMED_MODEL)
                .attributes(attributes)
                .build());
        listener.onError(EmbeddingModelErrorContext.builder()
                .error(new IllegalStateException("onnx session closed"))
                .textSegments(segments)
                .embeddingModel(UNNAMED_MODEL)
                .attributes(attributes)
                .build());

        // A span that is never ended is never exported, so the failing path has to close too.
        assertThat(onlySpan().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR")
                .containsEntry(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, "onnx session closed");
    }

    @Test
    @DisplayName("a response with no observation stashed for it exports nothing")
    void anUnpairedResponseIsIgnored() {
        var listener = listener(unpriced(), true);

        listener.onResponse(EmbeddingModelResponseContext.builder()
                .textSegments(segments("bom dia"))
                .embeddingModel(UNNAMED_MODEL)
                .attributes(new ConcurrentHashMap<>())
                .response(vectors(1))
                .build());

        // Another listener's map, or a callback pair this one never opened. Closing a span it
        // does not own would end somebody else's observation.
        assertThat(exported.getFinishedSpanItems()).isEmpty();
    }

    // --- helpers

    private LangfuseEmbeddingModelListener listener(CostCalculator costs, boolean captureContent) {
        return new LangfuseEmbeddingModelListener(
                MODEL, tracer, costs, new ObservationContentPolicy(captureContent, List.of()));
    }

    private static CostCalculator unpriced() {
        return new CostCalculator(List.of());
    }

    /**
     * One embedding call, as the listening wrapper drives it: both callbacks on the calling
     * thread, sharing one attributes map.
     */
    private void embed(LangfuseEmbeddingModelListener listener,
                       List<TextSegment> segments,
                       Response<List<Embedding>> response) {
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        listener.onRequest(EmbeddingModelRequestContext.builder()
                .textSegments(segments)
                .embeddingModel(UNNAMED_MODEL)
                .attributes(attributes)
                .build());
        listener.onResponse(EmbeddingModelResponseContext.builder()
                .textSegments(segments)
                .embeddingModel(UNNAMED_MODEL)
                .attributes(attributes)
                .response(response)
                .build());
    }

    private static List<TextSegment> segments(String... texts) {
        return List.of(texts).stream().map(TextSegment::from).toList();
    }

    private static Response<List<Embedding>> vectors(int count) {
        return Response.from(vectorList(count));
    }

    private static List<Embedding> vectorList(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> Embedding.from(new float[]{0.5f, 0.25f, 0.125f}))
                .toList();
    }

    private SpanData onlySpan() {
        assertThat(exported.getFinishedSpanItems()).hasSize(1);
        return exported.getFinishedSpanItems().getFirst();
    }
}
