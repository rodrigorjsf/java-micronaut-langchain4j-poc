package io.github.rodrigorjsf.agenticchat.rag;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

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

    private ApplicationContext ctx;
    private KnowledgeBase knowledge;
    private ContentRetriever retriever;

    @BeforeAll
    void setUp() {
        ctx = ApplicationContext.run(Map.of(
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake"));
        knowledge = ctx.getBean(KnowledgeBase.class);
        retriever = ctx.getBean(ContentRetriever.class);
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

    @ParameterizedTest(name = "\"{0}\" retrieves {1}")
    @CsvSource({
            "o que voce consegue fazer?,                          sobre-o-assistente.md",
            "quais sao suas limitacoes?,                          sobre-o-assistente.md",
            "o que significa o codigo IBGE de um municipio?,      dados-publicos-brasileiros.md",
            "por que o CEP veio sem rua e sem bairro?,            dados-publicos-brasileiros.md",
            "o que quer dizer o codigo 95 na previsao?,           como-ler-a-previsao.md",
            "a previsao esta em qual fuso horario?,               como-ler-a-previsao.md",
    })
    @DisplayName("a real question retrieves the document that answers it")
    void retrievesTheRightDocument(String question, String expectedSource) {
        var contents = retriever.retrieve(Query.from(question));

        assertThat(contents)
                .as("nothing retrieved for: %s", question)
                .isNotEmpty();

        var sources = contents.stream()
                .map(content -> content.textSegment().metadata().getString("source"))
                .toList();
        assertThat(sources)
                .as("question: %s", question)
                .contains(expectedSource);
    }

    @ParameterizedTest(name = "no match: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "qual e a receita de bolo de cenoura com cobertura de chocolate",
            "escreva um script em python para ler um csv",
    })
    @DisplayName("a clearly unrelated question retrieves nothing")
    void anUnrelatedQuestionRetrievesNothing(String question) {
        assertThat(retriever.retrieve(Query.from(question))).isEmpty();
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
        var contents = retriever.retrieve(Query.from(question));
        assertThat(contents).isNotEmpty();
        return (Double) contents.getFirst().metadata()
                .get(dev.langchain4j.rag.content.ContentMetadata.SCORE);
    }

    @Test
    void everySegmentCarriesItsSourceAndTitle() {
        var contents = retriever.retrieve(Query.from("o que voce consegue fazer"));

        assertThat(contents).allSatisfy(content -> {
            var metadata = content.textSegment().metadata();
            assertThat(metadata.getString("source")).endsWith(".md");
            assertThat(metadata.getString("title")).isNotBlank();
        });
    }

    @Test
    @DisplayName("retrieval carries a relevance score, so citations can be ranked")
    void contentsCarryAScore() {
        var contents = retriever.retrieve(Query.from("quais feriados voce conhece"));

        assertThat(contents).isNotEmpty();
        assertThat(contents.getFirst().metadata()).isNotEmpty();
    }
}
