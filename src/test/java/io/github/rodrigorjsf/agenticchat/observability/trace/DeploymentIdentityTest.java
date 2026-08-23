package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three strings stamped on every observation this application exports.
 *
 * <p>They are a filter in Langfuse and a label in Grafana, so a wrong value is not a
 * cosmetic problem — it splits one deployment into two in every aggregate that groups by
 * it, and neither half says it is a half.
 *
 * <p>The test exists because that happened. {@code application.yml} wrote the version
 * default as {@code ${AGENTIC_VERSION:`0.1`}}, borrowing the backtick escaping that the
 * EMPTY default two lines below genuinely needs, and Micronaut resolved it to the
 * four-character string {@code 0.1}} — the closing brace included. Read back off a real
 * instance, all 37 observations the containerised application had sent carried
 * {@code "version": "0.1}"}. Nothing failed, nothing logged, and every one of them was
 * attributed to a release that does not exist.
 */
class DeploymentIdentityTest {

    @Test
    @DisplayName("the shipped defaults resolve to the values they are written as")
    void theShippedDefaultsAreClean() {
        try (var ctx = ApplicationContext.run(Map.of(
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake",
                "agentic.test.stub-models", "true"))) {

            var identity = ctx.getBean(DeploymentIdentity.class);

            assertThat(identity.version()).isEqualTo("0.1");
            assertThat(identity.environment()).isEqualTo("default");
            // Empty, and that one IS spelled with backticks on purpose: `${VAR:}` is how
            // Micronaut writes an empty default, and the OTLP exporter refuses to build on
            // a two-backtick literal with "OTLP endpoint must be a valid URL".
            assertThat(identity.release()).isEmpty();
        }
    }

    @Test
    @DisplayName("an operator's environment variable still wins over the default")
    void theEnvironmentVariableWins() {
        try (var ctx = ApplicationContext.run(Map.of(
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake",
                "agentic.test.stub-models", "true",
                "agentic.observability.version", "2.4.1",
                "agentic.observability.environment", "staging"))) {

            var identity = ctx.getBean(DeploymentIdentity.class);

            assertThat(identity.version()).isEqualTo("2.4.1");
            assertThat(identity.environment()).isEqualTo("staging");
        }
    }
}
