package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire settings for exporting straight to a Langfuse instance.
 *
 * <p>Every value here is a contract with a server this codebase does not own, and each one fails
 * quietly when it is wrong: a missing ingestion-version header does not error, it delays the data
 * by up to ten minutes; a wrong path returns 4xx into a background exporter thread nobody reads.
 * So they are asserted rather than assumed.
 */
class LangfuseOtlpSettingsTest {

    private static final Map<String, Object> CREDENTIALS = Map.of(
            "agentic.llm.credentials.google-api-key", "fake",
            "agentic.llm.credentials.openai-api-key", "fake");

    private static Map<String, Object> withLangfuse(String host) {
        var config = new java.util.HashMap<String, Object>(CREDENTIALS);
        config.put("agentic.observability.langfuse.host", host);
        config.put("agentic.observability.langfuse.public-key", "pk-lf-1234567890");
        config.put("agentic.observability.langfuse.secret-key", "sk-lf-1234567890");
        return config;
    }

    @Test
    @DisplayName("traces go to the signal-specific OTLP path, not the base one")
    void tracesEndpointIsTheSignalSpecificPath() {
        try (var ctx = ApplicationContext.run(withLangfuse("http://localhost:3000"))) {
            assertThat(ctx.getBean(LangfuseOtlpSettings.class).tracesEndpoint())
                    .isEqualTo("http://localhost:3000/api/public/otel/v1/traces");
        }
    }

    @Test
    @DisplayName("a trailing slash on the host does not double the separator")
    void aTrailingSlashIsTolerated() {
        try (var ctx = ApplicationContext.run(withLangfuse("https://cloud.langfuse.com/"))) {
            assertThat(ctx.getBean(LangfuseOtlpSettings.class).tracesEndpoint())
                    .isEqualTo("https://cloud.langfuse.com/api/public/otel/v1/traces");
        }
    }

    @Test
    @DisplayName("the keys are sent as HTTP Basic, base64 of publicKey:secretKey")
    void authenticationIsBasic() {
        try (var ctx = ApplicationContext.run(withLangfuse("http://localhost:3000"))) {
            // echo -n "pk-lf-1234567890:sk-lf-1234567890" | base64
            assertThat(ctx.getBean(LangfuseOtlpSettings.class).headers())
                    .containsEntry("Authorization",
                            "Basic cGstbGYtMTIzNDU2Nzg5MDpzay1sZi0xMjM0NTY3ODkw");
        }
    }

    @Test
    @DisplayName("the v4 ingestion header is sent, or the data arrives up to ten minutes late")
    void theIngestionVersionHeaderIsSent() {
        try (var ctx = ApplicationContext.run(withLangfuse("http://localhost:3000"))) {
            assertThat(ctx.getBean(LangfuseOtlpSettings.class).headers())
                    .containsEntry("x-langfuse-ingestion-version", "4");
        }
    }

    @Test
    @DisplayName("without credentials there is no exporter, and no attempt to reach one")
    void noCredentialsMeansNoExport() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            // The default build must need no network. A Langfuse exporter configured by
            // default would open a connection on every test run.
            assertThat(ctx.findBean(LangfuseOtlpSettings.class)).isEmpty();
        }
    }
}
