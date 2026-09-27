package io.github.rodrigorjsf.agenticchat.rag;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Result;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Run;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes {@code retrieval-report.html}. An evals-profile run, not a default-build one: the
 * retrieval assertions stay in {@code KnowledgeBaseTest}, and only the report — the page a person
 * reads — is produced here, so {@code ./mvnw verify} leaves the working tree alone.
 *
 * <pre>
 * ./mvnw test -Pevals -Dtest=RetrievalReportEval                        # the report alone
 * ./mvnw test -Pevals -Dtest=RetrievalReportEval -Dretrieval.diff=main  # plus the rows each changed document puts at risk
 * </pre>
 */
@Tag("evals")
class RetrievalReportEval {

    /** {@code -Dretrieval.diff=<git ref>} adds the changed-documents section to the report. */
    static final String DIFF_PROPERTY = "retrieval.diff";
    /** Surefire runs with the module directory as the working directory. */
    private static final Path REPOSITORY_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path REPORT = REPOSITORY_ROOT.resolve("retrieval-report.html");

    @Test
    @DisplayName("writes retrieval-report.html at the repository root, one row per query")
    void writesTheReport() throws Exception {
        List<Result> results;
        String html;
        try (var ctx = RetrievalRun.startContext()) {
            results = RetrievalRun.retrieveEveryRow(ctx.getBean(ContentRetriever.class));
            String diffRef = System.getProperty(DIFF_PROPERTY, "").strip();
            var run = new Run(
                    ctx.getRequiredProperty("agentic.rag.min-score", Double.class),
                    ctx.getRequiredProperty("agentic.rag.max-results", Integer.class),
                    RetrievalRun.corpusDocuments(),
                    results,
                    diffRef.isEmpty() ? null : CorpusDiff.since(diffRef, REPOSITORY_ROOT));
            Files.writeString(REPORT, RetrievalReport.render(run), StandardCharsets.UTF_8);
            html = Files.readString(REPORT, StandardCharsets.UTF_8);
        }

        assertThat(results).isNotEmpty();
        assertThat(html).contains("<h1>Retrieval report</h1>");
        results.forEach(result -> assertThat(html).contains(result.query().id()));
    }
}
