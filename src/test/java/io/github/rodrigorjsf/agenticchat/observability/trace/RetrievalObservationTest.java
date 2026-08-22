package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.rodrigorjsf.agenticchat.observability.CostCalculator;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One retrieval, end to end, as the trace shows it.
 *
 * <p>Real {@link InMemoryEmbeddingStore}, real {@link EmbeddingStoreContentRetriever}, real
 * listening wrappers — everything except the embedding model, which is a three-dimension
 * stub. The quantized MiniLM takes about 5.7 s to load and would prove nothing here that a
 * deterministic vector does not: what is under test is that the three observations nest the
 * way the UI needs them to, and which fact lands on which of them.
 *
 * <p>The nesting is the fragile part. It holds because
 * {@code DefaultRetrievalAugmentor.process} runs {@code retrieve} inline on the calling
 * thread when there is one query and one retriever — this application's shape — so the
 * embedding call and the store search inherit the retrieval's OpenTelemetry context. With a
 * second retriever the augmentor switches to an executor with no context propagation and
 * this arrangement comes apart, which is why it is asserted rather than assumed.
 */
class RetrievalObservationTest {

    private static final String MODEL = "mini-embed";
    private static final String RETRIEVER = "assistant-knowledge";
    private static final String SEARCH = LangfuseEmbeddingStoreListener.OBSERVATION_NAME;

    /** The threshold {@code application.yml} configures, from a measurement on the real corpus. */
    private static final double MIN_SCORE = 0.72;

    private InMemorySpanExporter exported;
    private SdkTracerProvider tracerProvider;
    private AgentTracer tracer;

    private EmbeddingModel listeningModel;
    private EmbeddingStore<TextSegment> listeningStore;
    private ContentRetriever retriever;

    @BeforeEach
    void setUp() {
        exported = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exported))
                .build();
        tracer = new OtelAgentTracer(tracerProvider.get("test"), ObservationJson.compact());
        var content = new ObservationContentPolicy(true, List.of());

        var model = new KeywordEmbeddingModel();
        var store = new InMemoryEmbeddingStore<TextSegment>();
        // The corpus is fixture, not behaviour: it is added through the raw model and the raw
        // store so that these two segments existing produces no spans of its own.
        add(store, model, "alpha", "sobre-o-assistente.md");
        add(store, model, "beta", "dados-publicos-brasileiros.md");

        listeningModel = model.addListener(new LangfuseEmbeddingModelListener(
                MODEL, tracer, new CostCalculator(List.of()), content));
        listeningStore = store.addListener(new LangfuseEmbeddingStoreListener(tracer));
        retriever = EmbeddingStoreContentRetriever.builder()
                .displayName(RETRIEVER)
                .embeddingStore(listeningStore)
                .embeddingModel(listeningModel)
                .maxResults(3)
                .minScore(MIN_SCORE)
                .build()
                // The name is repeated here because displayName is unreachable from inside a
                // callback: every context hands back this wrapper, not the retriever it wraps.
                .addListener(new LangfuseRetrieverListener(RETRIEVER, tracer, content));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    @DisplayName("a retrieval nests the query's embedding and the store search under it, inside the turn")
    void theThreeObservationsNest() {
        try (var turnObservation = tracer.start("turn", ObservationType.AGENT)) {
            retriever.retrieve(Query.from("alpha"));
        }

        assertThat(exported.getFinishedSpanItems()).hasSize(4);
        var turn = spanNamed("turn");
        var retrieval = spanNamed(RETRIEVER);
        var embedding = spanNamed(MODEL);
        var search = spanNamed(SEARCH);

        // One trace, and a shape Langfuse can draw: an agent with a retriever under it, and
        // the embedding call and the vector search under that.
        assertThat(retrieval.getParentSpanId()).isEqualTo(turn.getSpanId());
        assertThat(embedding.getParentSpanId()).isEqualTo(retrieval.getSpanId());
        assertThat(search.getParentSpanId()).isEqualTo(retrieval.getSpanId());
        assertThat(List.of(retrieval, embedding, search))
                .allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(turn.getTraceId()));
    }

    @Test
    @DisplayName("each observation carries the fact that only it can see")
    void eachObservationCarriesWhatOnlyItKnows() {
        retriever.retrieve(Query.from("alpha"));

        // The query, and the scores it produced: the pair a threshold argument is settled with.
        var retrieval = spanNamed(RETRIEVER).getAttributes();
        assertThat(retrieval.get(LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("retriever");
        assertThat(retrieval.get(LangfuseAttributes.OBSERVATION_INPUT)).isEqualTo("\"alpha\"");
        assertThat(retrieval.get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .contains("\"segments\":1", "\"source\":\"sobre-o-assistente.md\"")
                // Exactly 1.0: identical vectors give a dot product of 1.0 over norms of
                // exactly 1.0, and (cs + 1) / 2 keeps it there. The pattern allows for the
                // JSON encoder writing an integral double either way; the value is the point,
                // not its spelling.
                .containsPattern("\"score\":1(\\.0)?[,}]");

        // The threshold in force, which EmbeddingStoreContentRetriever resolves per query and
        // publishes no getter for — the search request is the only place it surfaces.
        var search = spanNamed(SEARCH).getAttributes();
        assertThat(search.get(LangfuseAttributes.OBSERVATION_INPUT))
                .contains("\"min_score\":0.72", "\"max_results\":3", "\"dimensions\":3");
        assertThat(search.get(LangfuseAttributes.OBSERVATION_OUTPUT)).contains("\"matches\":1");
        // The retriever already wrote the user's words one level up; this span does not.
        assertThat(search.get(LangfuseAttributes.OBSERVATION_INPUT)).doesNotContain("alpha");

        // The tokens, the model, and a count of vectors instead of the vectors.
        var embedding = spanNamed(MODEL).getAttributes();
        assertThat(embedding.get(LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("embedding");
        assertThat(embedding.get(LangfuseAttributes.MODEL_NAME)).isEqualTo(MODEL);
        // "alpha" is five characters, and this stub counts characters the way the in-process
        // model counts BERT tokens: a number the model reports, not one a listener invented.
        assertThat(embedding.get(LangfuseAttributes.USAGE_DETAILS)).contains("\"input\":5", "\"total\":5");
        assertThat(embedding.get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .isEqualTo("{\"embeddings\":1,\"dimension\":3}");
    }

    @Test
    @DisplayName("a query nothing clears leaves both observations, both saying nothing was found")
    void aQueryBelowTheThresholdStillProducesObservations() {
        // Scores 0.5 against both segments, under the 0.72 threshold. The store discards what
        // it rejects, so no observation can say how close it came — only that it came to
        // nothing, which is the honest limit of a threshold applied inside the store.
        retriever.retrieve(Query.from("gamma"));

        assertThat(spanNamed(RETRIEVER).getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .contains("\"segments\":0");
        assertThat(spanNamed(SEARCH).getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .isEqualTo("{\"matches\":0}");
        // Retrieving nothing is the threshold working, not a failure.
        assertThat(spanNamed(RETRIEVER).getAttributes().get(LangfuseAttributes.OBSERVATION_LEVEL)).isNull();
    }

    @Test
    @DisplayName("ingesting the corpus is an embedding call and never a retrieval")
    void ingestionIsAnEmbeddingAndNotARetrieval() {
        var segments = List.of(TextSegment.from("alpha"), TextSegment.from("beta"));

        var embeddings = listeningModel.embedAll(segments).content();
        listeningStore.addAll(embeddings, segments);

        // KnowledgeBase does exactly this once at boot. The write is not a lookup, so the
        // store listener ignores it; the embedding call is real and is observed, with the
        // batch written as its shape rather than as the corpus text.
        assertThat(exported.getFinishedSpanItems()).hasSize(1);
        var embedding = spanNamed(MODEL).getAttributes();
        assertThat(embedding.get(LangfuseAttributes.OBSERVATION_INPUT))
                .contains("\"inputs\":2", "\"characters\":9")
                .doesNotContain("alpha", "beta");
    }

    // --- helpers

    private static void add(EmbeddingStore<TextSegment> store, EmbeddingModel model, String text, String source) {
        var segment = TextSegment.from(text, Metadata.from(Map.of("source", source)));
        store.add(model.embed(segment).content(), segment);
    }

    private SpanData spanNamed(String name) {
        return exported.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name + " among "
                        + exported.getFinishedSpanItems().stream().map(SpanData::getName).toList()));
    }

    /**
     * Three dimensions, one per keyword, so a score is arithmetic rather than a mystery:
     * a query matching a segment's keyword scores 1.0, anything else scores 0.5 and is
     * excluded by the threshold.
     *
     * <p>Only {@code embedAll} is overridden, which is exactly how
     * {@code AbstractInProcessEmbeddingModel} is built — every other entry point on the
     * interface defaults down to it — so this stub exercises the same dispatch path the real
     * model does. The token count is reported the same way too: a real number from the model,
     * not an estimate bolted on by a listener.
     */
    private static final class KeywordEmbeddingModel implements EmbeddingModel {

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            var vectors = segments.stream().map(segment -> vectorFor(segment.text())).toList();
            int tokens = segments.stream().mapToInt(segment -> segment.text().length()).sum();
            return Response.from(vectors, new TokenUsage(tokens));
        }

        private static Embedding vectorFor(String text) {
            if (text.contains("alpha")) {
                return Embedding.from(new float[]{1f, 0f, 0f});
            }
            if (text.contains("beta")) {
                return Embedding.from(new float[]{0f, 1f, 0f});
            }
            return Embedding.from(new float[]{0f, 0f, 1f});
        }
    }
}
