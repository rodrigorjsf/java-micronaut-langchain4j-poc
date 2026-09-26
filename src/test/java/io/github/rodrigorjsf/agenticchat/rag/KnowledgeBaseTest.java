package io.github.rodrigorjsf.agenticchat.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Chunk;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Query;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Result;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Run;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality, not just wiring.
 *
 * <p>The embedding model runs in-process and costs nothing per call, so these are
 * ordinary unit tests with no network and no fixtures — which is the point of
 * choosing an in-process model in the first place.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeBaseTest {

    private static final String QUERY_SET = "/evals/retrieval-queries.json";
    /** {@code -Dretrieval.diff=<git ref>} adds the changed-documents section to the report. */
    static final String DIFF_PROPERTY = "retrieval.diff";
    /** Surefire runs with the module directory as the working directory. */
    private static final Path REPOSITORY_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path REPORT = REPOSITORY_ROOT.resolve("retrieval-report.html");

    private ApplicationContext ctx;
    private KnowledgeBase knowledge;
    private ContentRetriever retriever;
    private List<Result> results;

    @BeforeAll
    void setUp() throws Exception {
        ctx = ApplicationContext.run(Map.of(
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake"));
        knowledge = ctx.getBean(KnowledgeBase.class);
        retriever = ctx.getBean(ContentRetriever.class);

        results = retrieveEveryRow();
        String diffRef = System.getProperty(DIFF_PROPERTY, "").strip();
        var run = new Run(
                ctx.getRequiredProperty("agentic.rag.min-score", Double.class),
                ctx.getRequiredProperty("agentic.rag.max-results", Integer.class),
                corpusDocuments(),
                results,
                diffRef.isEmpty() ? null : CorpusDiff.since(diffRef, REPOSITORY_ROOT));
        Files.writeString(REPORT, RetrievalReport.render(run), StandardCharsets.UTF_8);
    }

    @AfterAll
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Test
    void ingestsTheCorpusAtStartup() {
        assertThat(knowledge.segmentCount())
                .as("the corpus should split into a workable number of segments")
                .isBetween(5, 200);
    }

    @Test
    void embeddingsAre384Dimensions() {
        assertThat(knowledge.embeddingModel().embed("teste").content().dimension()).isEqualTo(384);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("positiveRows")
    @DisplayName("a real question retrieves the document that answers it")
    void retrievesTheRightDocument(String id, Result result) {
        var sources = result.chunks().stream().map(Chunk::source).toList();
        assertThat(result.passed())
                .as("%s: \"%s\" should retrieve %s, retrieved %s",
                        id, result.query().question(), result.query().expectedSource(), sources)
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("negativeRows")
    @DisplayName("a clearly unrelated question retrieves nothing")
    void anUnrelatedQuestionRetrievesNothing(String id, Result result) {
        assertThat(result.chunks())
                .as("%s: \"%s\" should retrieve nothing", id, result.query().question())
                .isEmpty();
    }

    @Test
    @DisplayName("every run writes retrieval-report.html at the repository root, one row per query")
    void writesTheReport() throws IOException {
        String html = Files.readString(REPORT, StandardCharsets.UTF_8);

        assertThat(results).isNotEmpty();
        assertThat(html).contains("<h1>Retrieval report</h1>");
        results.forEach(result -> assertThat(html).contains(result.query().id()));
    }

    @Test
    @DisplayName("every corpus document is some positive row's expected source, and every expected source exists")
    void queriesCoverTheCorpusBothWays() throws Exception {
        var expected = results.stream().map(r -> r.query().expectedSource())
                .filter(java.util.Objects::nonNull).distinct().toList();

        assertThat(expected)
                .as("a document no positive row names ships untested; see Uncovered documents in the report")
                .containsAll(corpusDocuments());
        assertThat(corpusDocuments())
                .as("an expected_source naming a document that no longer exists can never pass")
                .containsAll(expected);
    }

    Stream<Arguments> positiveRows() {
        return results.stream().filter(r -> r.query().positive()).map(r -> Arguments.of(r.query().id(), r));
    }

    Stream<Arguments> negativeRows() {
        return results.stream().filter(r -> !r.query().positive()).map(r -> Arguments.of(r.query().id(), r));
    }

    /**
     * Each committed row goes through the shipped retriever exactly once. The local
     * embedding model is deterministic, so a second retrieval would only repeat the
     * first; the assertions and the report both read these results.
     */
    private List<Result> retrieveEveryRow() throws IOException {
        var rows = new ArrayList<Result>();
        for (Query query : loadQuerySet()) {
            var chunks = retriever.retrieve(dev.langchain4j.rag.query.Query.from(query.question())).stream()
                    .map(content -> new Chunk(
                            content.textSegment().metadata().getString("source"),
                            content.textSegment().metadata().getString("title"),
                            ((Number) content.metadata().get(ContentMetadata.SCORE)).doubleValue(),
                            content.textSegment().text()))
                    .toList();
            rows.add(new Result(query, chunks));
        }
        return List.copyOf(rows);
    }

    private static List<Query> loadQuerySet() throws IOException {
        try (InputStream in = KnowledgeBaseTest.class.getResourceAsStream(QUERY_SET)) {
            assertThat(in).as("query set %s on the test classpath", QUERY_SET).isNotNull();
            var queries = new ArrayList<Query>();
            for (JsonNode row : new ObjectMapper().readTree(in).get("queries")) {
                JsonNode expected = row.get("expected_source");
                String label = row.get("label").asText();
                assertThat(label).as("row %s label", row.get("id").asText()).isIn("positive", "negative");
                queries.add(new Query(row.get("id").asText(), row.get("question").asText(),
                        label,
                        expected == null || expected.isNull() ? null : expected.asText(),
                        row.get("provenance").asText()));
            }
            return queries;
        }
    }

    /** Every document the corpus holds, read from the same classpath directory ingestion reads. */
    private static List<String> corpusDocuments() throws Exception {
        var url = KnowledgeBaseTest.class.getClassLoader().getResource("knowledge");
        assertThat(url).isNotNull();
        try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".md"))
                    .sorted().toList();
        }
    }

    @Test
    @DisplayName("the threshold alone cannot separate relevant from irrelevant — hence the router")
    void theScoreDistributionsOverlap() {
        // Measured, and the reason SkillAwareQueryRouter exists. An irrelevant
        // question outscores a relevant one on this corpus with this model, so no
        // choice of minScore separates them. Pinned as a test so that a future
        // change of embedding model or corpus reveals whether that is still true.
        var wide = dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever.builder()
                .embeddingStore(knowledge.store())
                .embeddingModel(knowledge.embeddingModel())
                .maxResults(1)
                .minScore(0.0)
                .build();

        double irrelevant = topScore(wide, "quem ganhou a copa do mundo de 1994");
        double relevant = topScore(wide, "por que o CEP veio sem rua e sem bairro?");

        assertThat(irrelevant)
                .as("if this ever drops below the relevant score, the router could be simplified away")
                .isGreaterThan(relevant);
    }

    private static double topScore(dev.langchain4j.rag.content.retriever.ContentRetriever retriever,
                                   String question) {
        var contents = retriever.retrieve(dev.langchain4j.rag.query.Query.from(question));
        assertThat(contents).isNotEmpty();
        return (Double) contents.getFirst().metadata()
                .get(dev.langchain4j.rag.content.ContentMetadata.SCORE);
    }

    @Test
    void everySegmentCarriesItsSourceAndTitle() {
        var contents = retriever.retrieve(dev.langchain4j.rag.query.Query.from("o que voce consegue fazer"));

        assertThat(contents).allSatisfy(content -> {
            var metadata = content.textSegment().metadata();
            assertThat(metadata.getString("source")).endsWith(".md");
            assertThat(metadata.getString("title")).isNotBlank();
        });
    }

    @Test
    @DisplayName("retrieval carries a relevance score, so citations can be ranked")
    void contentsCarryAScore() {
        var contents = retriever.retrieve(dev.langchain4j.rag.query.Query.from("quais feriados voce conhece"));

        assertThat(contents).isNotEmpty();
        assertThat(contents.getFirst().metadata()).isNotEmpty();
    }
}
