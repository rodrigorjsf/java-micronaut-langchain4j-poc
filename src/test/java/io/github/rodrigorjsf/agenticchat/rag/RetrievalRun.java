package io.github.rodrigorjsf.agenticchat.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Chunk;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Query;
import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Result;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed query set run through the shipped retriever. Shared by {@code KnowledgeBaseTest},
 * which asserts on the results in the default build, and {@code RetrievalReportEval}, which writes
 * them as the report in the evals profile — so both read the same rows the same way.
 */
final class RetrievalRun {

    static final String QUERY_SET = "/evals/retrieval-queries.json";

    private RetrievalRun() {
    }

    /**
     * Each committed row goes through the retriever exactly once. The local embedding model is
     * deterministic, so a second retrieval would only repeat the first.
     */
    static List<Result> retrieveEveryRow(ContentRetriever retriever) throws IOException {
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

    /** Every document the corpus holds, read from the same classpath directory ingestion reads. */
    static List<String> corpusDocuments() throws Exception {
        var url = RetrievalRun.class.getClassLoader().getResource("knowledge");
        assertThat(url).isNotNull();
        try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".md"))
                    .sorted().toList();
        }
    }

    private static List<Query> loadQuerySet() throws IOException {
        try (InputStream in = RetrievalRun.class.getResourceAsStream(QUERY_SET)) {
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
}
