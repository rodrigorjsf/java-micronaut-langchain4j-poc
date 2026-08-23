package io.github.rodrigorjsf.agenticchat;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An empty default in a Micronaut placeholder is written {@code ${VAR:}}.
 *
 * <p>{@code ${VAR:``}} looks like the escaped form of the same thing and is not. Backticks
 * escape a default that CONTAINS a colon; an empty pair is not an escape hatch for "no
 * value", it is a two-character literal. Measured below.
 *
 * <p>Both spellings are silent, which is why this test exists. The two places the wrong one
 * had reached in this repository failed in different ways and neither said so:
 *
 * <ul>
 *   <li>{@code agentic.llm.credentials.google-api-key} — {@code ProviderCredentials.has()}
 *       asks {@code isBlank()}, and {@code ``} is not blank. With no key set, the registry
 *       therefore builds a Gemini model with the literal key {@code ``} instead of failing
 *       the deployment with "set it in the untracked .env", and the first user gets a 400
 *       from the provider;</li>
 *   <li>{@code agentic.observability.otlp.endpoint} — an exporter was built on {@code ``}
 *       and the OpenTelemetry SDK rejected it with "OTLP endpoint must be a valid URL",
 *       taking down every bean that needed an HTTP client. 42 tests with nothing to do with
 *       tracing failed on one unset environment variable.</li>
 * </ul>
 */
class ConfigurationPlaceholderTest {

    @Test
    @DisplayName("${VAR:} is empty and ${VAR:``} is two backticks")
    void anEmptyDefaultIsWrittenWithABareColon() {
        try (var ctx = ApplicationContext.run(Map.of(
                "probe.correct", "${A_VARIABLE_THAT_IS_NOT_SET:}",
                "probe.wrong", "${A_VARIABLE_THAT_IS_NOT_SET:``}"))) {

            assertThat(ctx.getEnvironment().getProperty("probe.correct", String.class))
                    .hasValue("");
            assertThat(ctx.getEnvironment().getProperty("probe.wrong", String.class))
                    .hasValue("``");
        }
    }

    @Test
    @DisplayName("no placeholder in application.yml uses the backtick spelling")
    void theShippedConfigurationUsesTheCorrectSpelling() throws IOException {
        String yaml = read("application.yml");

        // A grep in a test rather than in a script, because the failure it prevents is a
        // configuration value that is present, wrong, and never reported.
        assertThat(yaml.replace("`${VAR:``}`", ""))
                .as("an empty placeholder default must be written ${VAR:}, not ${VAR:``}")
                .doesNotContain(":``}");
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = ConfigurationPlaceholderTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " is on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
