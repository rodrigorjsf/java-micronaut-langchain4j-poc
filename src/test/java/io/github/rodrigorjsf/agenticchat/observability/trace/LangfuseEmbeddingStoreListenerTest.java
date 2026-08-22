package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreErrorContext;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreRequestContext;
import dev.langchain4j.store.embedding.listener.EmbeddingStoreResponseContext;
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
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The vector-store search observation, asserted through exported spans.
 *
 * <p>This listener earns its place on one fact: the threshold and the result cap in force
 * for a search are reachable from the {@link EmbeddingSearchRequest} and from nowhere else —
 * {@code EmbeddingStoreContentRetriever} resolves them per query and publishes no getter for
 * either. So most of what is asserted here is that those two numbers reach the trace, and
 * that everything the retriever observation already carries does not reach it twice.
 */
class LangfuseEmbeddingStoreListenerTest {

    private static final String QUERY = "o que voce consegue fazer?";

    /**
     * The contexts require a store instance and the listener asks it nothing. A real
     * in-memory store rather than a stub because it costs nothing to construct one.
     */
    private final EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

    private InMemorySpanExporter exported;
    private SdkTracerProvider tracerProvider;
    private LangfuseEmbeddingStoreListener listener;

    @BeforeEach
    void setUp() {
        exported = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exported))
                .build();
        listener = new LangfuseEmbeddingStoreListener(
                new OtelAgentTracer(tracerProvider.get("test"), ObservationJson.compact()));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    @DisplayName("a search is one retriever observation carrying the threshold that was in force")
    void aSearchCarriesTheThresholdInForce() {
        search(request(0.72, 3), matches(0.81));

        var span = onlySpan();
        assertThat(span.getName()).isEqualTo("embedding-store-search");
        assertThat(span.getAttributes().asMap())
                .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "retriever")
                // Filterable, because "every search that ran at 0.72" is the question a
                // threshold change is argued with, and an ordinary attribute cannot be
                // filtered on in Langfuse at all.
                .containsEntry(LangfuseAttributes.observationMetadata("min_score"), "0.72")
                .containsEntry(LangfuseAttributes.observationMetadata("max_results"), "3");
        assertThat(span.getAttributes().get(LangfuseAttributes.OBSERVATION_INPUT))
                .contains("\"min_score\":0.72", "\"max_results\":3", "\"dimensions\":3");
    }

    @Test
    @DisplayName("the query text is not repeated on the search, only the parameters that are unique to it")
    void theQueryTextIsNotRepeated() {
        search(request(0.72, 3), matches(0.81));

        // The retriever observation this span nests inside already has the user's words as
        // its input, microseconds earlier. A second copy is payload and a second copy of
        // personal data for nothing.
        assertThat(onlySpan().getAttributes().get(LangfuseAttributes.OBSERVATION_INPUT))
                .doesNotContain(QUERY);
    }

    @Test
    @DisplayName("the output is how many matches survived and the range they scored in")
    void theOutputIsTheCountAndTheScoreRange() {
        search(request(0.72, 3), matches(0.81, 0.74));

        // Not the matches themselves: the parent retriever observation writes a score, a
        // source and an id per segment already. The range is what pairs with the threshold
        // beside it to mean something.
        assertThat(onlySpan().getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .contains("\"matches\":2", "\"top_score\":0.81", "\"lowest_score\":0.74")
                .doesNotContain("embedding_id", "seg-");
    }

    @Test
    @DisplayName("a search that clears nothing writes the count and no score range")
    void anEmptyResultHasNoScoreRange() {
        search(request(0.72, 3), List.of());

        // The store applies minScore itself, so a segment that scored 0.71 against 0.72 is
        // discarded inside search() and appears nowhere. What this observation establishes is
        // that 0.72 was in force and nothing cleared it — not what the near miss was.
        assertThat(onlySpan().getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .isEqualTo("{\"matches\":0}");
    }

    @Test
    @DisplayName("an ingestion write is not a lookup and is not observed at all")
    void anIngestionWriteIsNotObserved() {
        var attributes = new ConcurrentHashMap<Object, Object>();
        var addAll = new EmbeddingStoreRequestContext.AddAll<>(
                store, attributes, List.of("id-1"), List.of(vector()), List.of(TextSegment.from("segmento")));

        listener.onRequest(addAll);
        listener.onResponse(new EmbeddingStoreResponseContext.AddAll<>(addAll, attributes, List.of("id-1")));

        // KnowledgeBase calls addAll once at boot with the whole corpus. Observing it would
        // put a retriever observation — defined as a lookup that does not change state — on
        // an ingestion write on every start. Nothing is stashed, so nothing is closed either.
        assertThat(exported.getFinishedSpanItems()).isEmpty();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("a failed search is recorded at ERROR level and the observation still closes")
    void aFailureIsRecordedAndTheSpanIsStillEnded() {
        var attributes = new ConcurrentHashMap<Object, Object>();
        var requestContext = new EmbeddingStoreRequestContext.Search<>(store, attributes, request(0.72, 3));

        listener.onRequest(requestContext);
        listener.onError(new EmbeddingStoreErrorContext<>(
                new IllegalStateException("index unavailable"), requestContext, attributes));

        assertThat(onlySpan().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR")
                .containsEntry(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, "index unavailable");
    }

    // --- helpers

    /**
     * One search, as the listening store drives it: both callbacks on the calling thread,
     * sharing one attributes map.
     */
    private void search(EmbeddingSearchRequest request, List<EmbeddingMatch<TextSegment>> matches) {
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        var requestContext = new EmbeddingStoreRequestContext.Search<>(store, attributes, request);
        listener.onRequest(requestContext);
        listener.onResponse(new EmbeddingStoreResponseContext.Search<>(
                requestContext, attributes, new EmbeddingSearchResult<>(matches)));
    }

    /**
     * Shaped as {@code EmbeddingStoreContentRetriever} shapes one — including the query text,
     * which it does set and which this observation deliberately does not write.
     */
    private static EmbeddingSearchRequest request(double minScore, int maxResults) {
        return EmbeddingSearchRequest.builder()
                .query(QUERY)
                .queryEmbedding(vector())
                .minScore(minScore)
                .maxResults(maxResults)
                .build();
    }

    private static List<EmbeddingMatch<TextSegment>> matches(double... scores) {
        var matches = new java.util.ArrayList<EmbeddingMatch<TextSegment>>(scores.length);
        for (double score : scores) {
            matches.add(new EmbeddingMatch<>(score, "seg-" + score, vector(), TextSegment.from("segmento")));
        }
        return matches;
    }

    private static Embedding vector() {
        return Embedding.from(new float[]{1f, 0f, 0f});
    }

    private SpanData onlySpan() {
        assertThat(exported.getFinishedSpanItems()).hasSize(1);
        return exported.getFinishedSpanItems().getFirst();
    }
}
