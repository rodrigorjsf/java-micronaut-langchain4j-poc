package io.github.rodrigorjsf.agenticchat.rag;

import io.github.rodrigorjsf.agenticchat.rag.RetrievalReport.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusDiffTest {

    private static final List<Query> QUERIES = List.of(
            new Query("pos-a1", "a?", "positive", "a.md", "t"),
            new Query("pos-a2", "a again?", "positive", "a.md", "t"),
            new Query("pos-b1", "b?", "positive", "b.md", "t"),
            new Query("neg-1", "cake?", "negative", null, "t"));

    @Test
    @DisplayName("a changed document names the positive rows that expect it, and every negative row")
    void changedDocumentNamesItsDependentQueries() {
        var diff = new CorpusDiff("main", List.of("a.md"));

        assertThat(diff.dependents(QUERIES))
                .isEqualTo(Map.of("a.md", List.of("pos-a1", "pos-a2", "neg-1")));
    }

    @Test
    @DisplayName("a new document no positive row expects still names the negatives it could start to answer")
    void newDocumentWithoutRows() {
        var diff = new CorpusDiff("main", List.of("novo.md"));

        assertThat(diff.dependents(QUERIES)).isEqualTo(Map.of("novo.md", List.of("neg-1")));
    }
}
