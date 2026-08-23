package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one configuration nothing else in this suite boots: a full context with Langfuse
 * credentials actually set.
 *
 * <p>Two things exist only here, and both would first appear at a production startup.
 *
 * <p><b>Bean uniqueness.</b> {@link NoOpScoreWriter} is
 * {@code @Requires(missingBeans = ScoreWriter.class)} and {@link LangfuseScoreWriter} is a
 * definition of the same type. If that guard does not do what it looks like it does, the
 * result is a {@code NonUniqueBeanException} on the first injection — in the deployment that
 * has credentials, which is every deployment that matters and none of the tests.
 *
 * <p><b>Whether configuring an exporter makes the process dial.</b> Everything else in this
 * codebase is arranged so the default build opens no connection. That promise is only worth
 * something if the configured path is also understood, so this measures it rather than
 * assuming: a real server socket that is never connected to, and an assertion that nothing
 * knocked.
 */
class LangfuseConfiguredContextTest {

    private static Map<String, Object> configuredAgainst(String host) {
        var config = new HashMap<String, Object>();
        config.put("agentic.llm.credentials.google-api-key", "fake");
        config.put("agentic.llm.credentials.openai-api-key", "fake");
        config.put("agentic.test.stub-models", "true");
        config.put("agentic.observability.langfuse.host", host);
        config.put("agentic.observability.langfuse.public-key", "pk-lf-test");
        config.put("agentic.observability.langfuse.secret-key", "sk-lf-test");
        return config;
    }

    @Test
    @DisplayName("with credentials set there is exactly one ScoreWriter, and it is the Langfuse one")
    void theLangfuseWriterReplacesTheNoOpAndDoesNotSitBesideIt() {
        try (var ctx = ApplicationContext.run(configuredAgainst("http://localhost:1"))) {
            assertThat(ctx.getBeansOfType(ScoreWriter.class))
                    .as("a second ScoreWriter definition is a NonUniqueBeanException at startup")
                    .hasSize(1);
            assertThat(ctx.getBean(ScoreWriter.class)).isInstanceOf(LangfuseScoreWriter.class);
        }
    }

    @Test
    @DisplayName("without credentials the no-op writer is the one that exists")
    void withoutCredentialsTheNoOpIsSelected() {
        try (var ctx = ApplicationContext.run(Map.of(
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake"))) {

            assertThat(ctx.getBeansOfType(ScoreWriter.class)).hasSize(1);
            assertThat(ctx.getBean(ScoreWriter.class)).isInstanceOf(NoOpScoreWriter.class);
        }
    }

    @Test
    @DisplayName("the OTLP settings resolve to the signal-specific path and the v4 header")
    void theSettingsBeanIsPresentAndCorrect() {
        try (var ctx = ApplicationContext.run(configuredAgainst("http://localhost:1"))) {
            var settings = ctx.getBean(LangfuseOtlpSettings.class);

            assertThat(settings.tracesEndpoint()).isEqualTo("http://localhost:1/api/public/otel/v1/traces");
            assertThat(settings.headers()).containsKey("Authorization")
                    .containsEntry("x-langfuse-ingestion-version", "4");
        }
    }

    @Test
    @DisplayName("configuring an exporter does not make the process knock on the door")
    void startingWithAnExporterConfiguredOpensNoConnection() throws IOException {
        // A real listener that is never connected to. accept() blocking out is the
        // assertion: the OTLP HTTP exporter connects on its first export, and a context
        // that has started and traced nothing has nothing to export.
        try (var door = new ServerSocket(0)) {
            door.setSoTimeout(3_000);
            String host = "http://localhost:" + door.getLocalPort();

            try (var ctx = ApplicationContext.run(configuredAgainst(host))) {
                // Force the whole tracing chain to be built rather than left lazy: the
                // customizer only runs when Micronaut assembles the OpenTelemetry SDK.
                assertThat(ctx.getBean(io.opentelemetry.api.OpenTelemetry.class)).isNotNull();
                assertThat(ctx.getBean(AgentTracer.class)).isNotNull();
                assertThat(ctx.getBean(ScoreWriter.class)).isNotNull();

                try {
                    var knocked = door.accept();
                    knocked.close();
                    throw new AssertionError(
                            "something connected to the configured endpoint during startup; "
                                    + "the default build's no-network promise depends on this not happening");
                } catch (SocketTimeoutException expected) {
                    // Nothing knocked in three seconds. That is the assertion.
                }
            }
        }
    }
}
