package io.github.rodrigorjsf.agenticchat.rag;

import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Chunk;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Query;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Result;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Run;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retrieval report is a pure function from a run to one self-contained HTML
 * string, so everything a reader needs to see why a query failed is asserted here
 * without an embedding model.
 */
class RetrievalReportTest {

    private static final Query CEP = new Query("pos-cep", "por que o CEP veio sem rua?",
            "positive", "dados.md", "document claim");
    private static final Query CAKE = new Query("neg-cake", "receita de bolo",
            "negative", null, "far negative");

    private static final Chunk CEP_CHUNK = new Chunk("dados.md", "Dados publicos", 0.8123, "O CEP geral...");
    private static final Chunk WRONG_CHUNK = new Chunk("sobre.md", "Sobre", 0.7301, "Eu consigo...");

    private static Run run(List<Result> results, List<String> corpus) {
        return new Run(0.72, 3, corpus, results, null);
    }

    @Test
    @DisplayName("a passing positive row shows the question, the expected document, each chunk with its score, the threshold and PASS")
    void positiveRow() {
        String html = RetrievalReport.render(run(
                List.of(new Result(CEP, List.of(CEP_CHUNK))), List.of("dados.md")));

        assertThat(html)
                .contains("pos-cep")
                .contains("por que o CEP veio sem rua?")
                .contains("dados.md")
                .contains("0.8123")
                .contains("0.72")
                .contains("PASS");
    }

    @Test
    @DisplayName("a negative row expects nothing, and fails when anything comes back")
    void negativeRowThatRetrieved() {
        var result = new Result(CAKE, List.of(WRONG_CHUNK));
        String html = RetrievalReport.render(run(List.of(result), List.of("sobre.md")));

        assertThat(result.passed()).isFalse();
        assertThat(html)
                .contains("nothing")
                .contains("0.7301")
                .contains("FAIL");
    }

    @Test
    @DisplayName("pass means: a positive retrieved its expected document; a negative retrieved nothing")
    void passRule() {
        assertThat(new Result(CEP, List.of(WRONG_CHUNK, CEP_CHUNK)).passed()).isTrue();
        assertThat(new Result(CEP, List.of(WRONG_CHUNK)).passed()).isFalse();
        assertThat(new Result(CEP, List.of()).passed()).isFalse();
        assertThat(new Result(CAKE, List.of()).passed()).isTrue();
    }

    @Test
    @DisplayName("coverage is reported per corpus document, and a document no positive row expects is listed as uncovered")
    void coverageAndUncoveredDocuments() {
        var cepMiss = new Query("pos-cep-2", "CEP sem bairro", "positive", "dados.md", "t");
        String html = RetrievalReport.render(run(
                List.of(new Result(CEP, List.of(CEP_CHUNK)), new Result(cepMiss, List.of(WRONG_CHUNK))),
                List.of("dados.md", "orfao.md")));

        assertThat(html)
                .contains("<h2>Coverage per document</h2>")
                .containsPattern("<td>dados\\.md</td><td>2</td><td>1</td>")
                .containsPattern("<td>orfao\\.md</td><td>0</td><td>0</td>")
                .containsPattern("(?s)<h2>Uncovered documents</h2>.*orfao\\.md");
    }

    @Test
    @DisplayName("in diff mode, each changed document names its dependent queries and their current result")
    void diffSection() {
        var results = List.of(new Result(CEP, List.of(CEP_CHUNK)), new Result(CAKE, List.of(WRONG_CHUNK)));
        String html = RetrievalReport.render(new Run(0.72, 3, List.of("dados.md"), results,
                new CorpusDiff("main", List.of("dados.md"))));

        assertThat(html)
                .containsPattern("(?s)<h2>Changed since main</h2>.*dados\\.md.*pos-cep.*PASS.*neg-cake.*FAIL");
    }

    @Test
    @DisplayName("without diff mode there is no changed-documents section")
    void noDiffSection() {
        String html = RetrievalReport.render(run(List.of(new Result(CEP, List.of(CEP_CHUNK))), List.of("dados.md")));

        assertThat(html).doesNotContain("Changed since");
    }

    @Test
    @DisplayName("question and chunk text are escaped, and the page loads nothing from the network")
    void escapesAndIsSelfContained() {
        var hostile = new Query("pos-x", "<script>alert(1)</script>", "positive", "dados.md", "t");
        var chunk = new Chunk("dados.md", "T", 0.9, "<img src=x onerror=y> & more");
        String html = RetrievalReport.render(run(List.of(new Result(hostile, List.of(chunk))), List.of("dados.md")));

        assertThat(html)
                .doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<img src=x")
                .contains("&amp; more")
                .doesNotContainPattern("(src|href)=\"https?://");
    }
}
