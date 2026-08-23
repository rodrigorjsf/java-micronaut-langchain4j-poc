package io.github.rodrigorjsf.agenticchat.observability.trace;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverErrorContext;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverRequestContext;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverResponseContext;
import dev.langchain4j.rag.query.Query;
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
 * The retrieval observation, asserted through exported spans.
 *
 * <p>The assertions are mostly about the scores, because the scores are what this
 * observation exists to record: the 0.72 threshold in {@code application.yml} came from a
 * measurement on this corpus, and the only way anyone re-opens that number with evidence
 * from real traffic is if the scores were written down when the traffic happened.
 */
class LangfuseRetrieverListenerTest {

    private static final String RETRIEVER = "assistant-knowledge";

    /**
     * {@link ContentRetriever} is a functional interface and the contexts only require an
     * instance. The listener never asks it anything — it could not: every context hands back
     * the listening wrapper rather than the {@code EmbeddingStoreContentRetriever} behind it,
     * which is why the observation's name is a constructor argument.
     */
    private static final ContentRetriever RETRIEVER_INSTANCE = query -> List.of();

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
    @DisplayName("a retrieval is one retriever observation carrying the query as its input")
    void aRetrievalIsOneRetrieverObservation() {
        retrieve(listener(true), "o que voce consegue fazer?", List.of(segment(0.81, "sobre-o-assistente.md")));

        var span = onlySpan();
        assertThat(span.getName()).isEqualTo(RETRIEVER);
        assertThat(span.getAttributes().asMap())
                // Not "span": a type outside span/event/generation is what makes Langfuse
                // draw the agent graph for the trace at all.
                .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "retriever")
                .containsEntry(LangfuseAttributes.OBSERVATION_INPUT, "\"o que voce consegue fazer?\"");
    }

    @Test
    @DisplayName("every retrieved segment is written with its score, its source and the store's id")
    void everySegmentCarriesItsScore() {
        retrieve(listener(true), "o que voce consegue fazer?", List.of(
                segment(0.81, "sobre-o-assistente.md"),
                segment(0.74, "dados-publicos-brasileiros.md")));

        assertThat(onlySpan().getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .contains("\"segments\":2")
                .contains("\"score\":0.81", "\"source\":\"sobre-o-assistente.md\"")
                .contains("\"score\":0.74", "\"source\":\"dados-publicos-brasileiros.md\"")
                // The store's own id, which is how a segment retrieved twice in two traces is
                // recognised as the same segment.
                .contains("\"embedding_id\":\"seg-0.81\"");
    }

    @Test
    @DisplayName("retrieval order is preserved, because which segment the model saw first is part of the question")
    void retrievalOrderIsPreserved() {
        retrieve(listener(true), "o que voce consegue fazer?", List.of(
                segment(0.81, "sobre-o-assistente.md"),
                segment(0.74, "dados-publicos-brasileiros.md")));

        String output = onlySpan().getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT);
        assertThat(output.indexOf("0.81")).isLessThan(output.indexOf("0.74"));
    }

    @Test
    @DisplayName("the segment count and the best score are filterable metadata, not only payload")
    void theCountAndTopScoreAreFilterable() {
        retrieve(listener(true), "o que voce consegue fazer?", List.of(
                segment(0.74, "dados-publicos-brasileiros.md"),
                segment(0.81, "sobre-o-assistente.md")));

        // An ordinary attribute lands in Langfuse's non-filterable metadata.attributes
        // catch-all; these two are the ones a threshold argument is actually filtered by.
        assertThat(onlySpan().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.observationMetadata("segments"), "2")
                .containsEntry(LangfuseAttributes.observationMetadata("top_score"), "0.81");
    }

    @Test
    @DisplayName("retrieving nothing is an empty result rather than an error")
    void retrievingNothingIsNotAFailure() {
        retrieve(listener(true), "quem ganhou a copa do mundo de 1994", List.of());

        var attributes = onlySpan().getAttributes();
        assertThat(attributes.get(LangfuseAttributes.OBSERVATION_OUTPUT)).contains("\"segments\":0");
        // The threshold doing its job looks exactly like this, so it is not raised to a
        // WARNING level. And the best score is unknowable rather than zero: the store
        // discards what it rejects, so nothing here can say how close the near miss was.
        assertThat(attributes.get(LangfuseAttributes.OBSERVATION_LEVEL)).isNull();
        assertThat(attributes.get(LangfuseAttributes.observationMetadata("top_score"))).isNull();
        assertThat(attributes.asMap())
                .containsEntry(LangfuseAttributes.observationMetadata("segments"), "0");
    }

    @Test
    @DisplayName("the retrieved text is not repeated on the retrieval observation")
    void theRetrievedTextIsNotRepeated() {
        retrieve(listener(true), "o que voce consegue fazer?", List.of(segment(0.81, "sobre-o-assistente.md")));

        // The injector splices these segments into the user message, and the next generation
        // observation writes that message verbatim as its input. Writing them here as well
        // would double the payload of every retrieving turn to say the same thing twice.
        assertThat(onlySpan().getAttributes().get(LangfuseAttributes.OBSERVATION_OUTPUT))
                .doesNotContain("Este assistente responde")
                .contains("\"characters\":");
    }

    @Test
    @DisplayName("with content capture off the query is withheld and the scores are not")
    void contentCaptureOffStillLeavesTheScores() {
        retrieve(listener(false), "o que voce consegue fazer?", List.of(segment(0.81, "sobre-o-assistente.md")));

        var attributes = onlySpan().getAttributes();
        assertThat(attributes.get(LangfuseAttributes.OBSERVATION_INPUT)).isNull();
        // Scores, ids and lengths are not content: withholding them would leave a deployment
        // that turns capture off with no way to argue about its own threshold.
        assertThat(attributes.get(LangfuseAttributes.OBSERVATION_OUTPUT)).contains("\"score\":0.81");
    }

    @Test
    @DisplayName("a failed retrieval is recorded at ERROR level and the observation still closes")
    void aFailureIsRecordedAndTheSpanIsStillEnded() {
        var listener = listener(true);
        var attributes = new ConcurrentHashMap<Object, Object>();
        var query = Query.from("o que voce consegue fazer?");

        listener.onRequest(ContentRetrieverRequestContext.builder()
                .query(query)
                .contentRetriever(RETRIEVER_INSTANCE)
                .attributes(attributes)
                .build());
        listener.onError(ContentRetrieverErrorContext.builder()
                .error(new IllegalStateException("vector store unreachable"))
                .query(query)
                .contentRetriever(RETRIEVER_INSTANCE)
                .attributes(attributes)
                .build());

        assertThat(onlySpan().getAttributes().asMap())
                .containsEntry(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR")
                .containsEntry(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, "vector store unreachable");
    }

    // --- helpers

    private LangfuseRetrieverListener listener(boolean captureContent) {
        return new LangfuseRetrieverListener(
                RETRIEVER, tracer, new ObservationContentPolicy(captureContent, List.of()));
    }

    /**
     * One retrieval, as the listening retriever drives it: both callbacks on the calling
     * thread, sharing one attributes map.
     */
    private void retrieve(LangfuseRetrieverListener listener, String queryText, List<Content> contents) {
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        var query = Query.from(queryText);
        listener.onRequest(ContentRetrieverRequestContext.builder()
                .query(query)
                .contentRetriever(RETRIEVER_INSTANCE)
                .attributes(attributes)
                .build());
        listener.onResponse(ContentRetrieverResponseContext.builder()
                .contents(contents)
                .query(query)
                .contentRetriever(RETRIEVER_INSTANCE)
                .attributes(attributes)
                .build());
    }

    /**
     * A retrieved segment shaped exactly as {@code EmbeddingStoreContentRetriever} shapes
     * one: the score and the store's id in {@link ContentMetadata}, the source document in
     * the segment's own metadata, where {@code KnowledgeBase} puts it.
     */
    private static Content segment(double score, String source) {
        return Content.from(
                TextSegment.from("Este assistente responde sobre dados publicos brasileiros.",
                        Metadata.from(Map.of("source", source))),
                Map.<ContentMetadata, Object>of(
                        ContentMetadata.SCORE, score,
                        ContentMetadata.EMBEDDING_ID, "seg-" + score));
    }

    private SpanData onlySpan() {
        assertThat(exported.getFinishedSpanItems()).hasSize(1);
        return exported.getFinishedSpanItems().getFirst();
    }
}
