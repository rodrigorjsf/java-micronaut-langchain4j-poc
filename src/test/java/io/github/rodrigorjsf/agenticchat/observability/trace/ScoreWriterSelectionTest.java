package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@link ScoreWriter} a deployment gets, asserted in both directions.
 *
 * <p>One direction is not enough, and the reason is asymmetric. If the no-op writer were
 * created alongside the Langfuse one, a context with credentials would fail with
 * {@code NonUniqueBeanException} — a failure the no-credentials test cannot see. If the
 * gate were inverted, a deployment that paid for a Langfuse instance would silently
 * discard every score, which no startup error would report at all.
 */
class ScoreWriterSelectionTest {

    @Test
    @DisplayName("with no Langfuse credentials the no-op writer is the ScoreWriter")
    void withoutCredentialsTheNoOpWriterIsSelected() {
        try (var ctx = ApplicationContext.run(baseConfiguration())) {
            // The shipped application.yml leaves all three Langfuse properties empty, so
            // this is the default build's own configuration rather than a contrived one.
            assertThat(ctx.getBean(ScoreWriter.class)).isInstanceOf(NoOpScoreWriter.class);
        }
    }

    @Test
    @DisplayName("with Langfuse credentials the Langfuse writer is the only ScoreWriter")
    void withCredentialsTheLangfuseWriterIsSelected() {
        Map<String, Object> config = baseConfiguration();
        // Nothing listens on port 1. Selection is decided by configuration alone, and this
        // writer never opens a connection until it has a score to send.
        config.put("agentic.observability.langfuse.host", "http://localhost:1");
        config.put("agentic.observability.langfuse.public-key", "pk-lf-selection");
        config.put("agentic.observability.langfuse.secret-key", "sk-lf-selection");

        try (var ctx = ApplicationContext.run(config)) {
            assertThat(ctx.getBean(ScoreWriter.class)).isInstanceOf(LangfuseScoreWriter.class);
            assertThat(ctx.getBeansOfType(ScoreWriter.class)).hasSize(1);
        }
    }

    private static Map<String, Object> baseConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("agentic.test.record-spans", "true");
        config.put("agentic.llm.credentials.google-api-key", "fake");
        config.put("agentic.llm.credentials.openai-api-key", "fake");
        return config;
    }
}
