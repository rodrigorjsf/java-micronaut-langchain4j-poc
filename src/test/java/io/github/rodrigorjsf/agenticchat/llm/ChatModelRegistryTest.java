package io.github.rodrigorjsf.agenticchat.llm;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wiring tests only — no network. Building a {@code ChatModel} does not call the
 * provider, so an obviously fake key is enough to prove the configuration binds.
 *
 * <p>The extra roles are named {@code probe*} rather than {@code judge}/{@code agent}:
 * {@code application.yml} already defines those, and {@code @EachProperty} entries
 * merge rather than replace, so reusing the names would silently test a mixture of
 * the shipped config and the test's.
 */
class ChatModelRegistryTest {

    private static final Map<String, Object> CREDENTIALS = Map.of(
            "agentic.llm.credentials.google-api-key", "fake",
            "agentic.llm.credentials.openai-api-key", "fake");

    @Test
    void theShippedConfigurationBuildsEveryRole() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var registry = ctx.getBean(ChatModelRegistry.class);

            assertThat(registry.roles()).contains("judge", "agent");
            assertThat(registry.forRole("judge")).isNotNull();
            assertThat(registry.forRole("agent")).isNotNull();
            assertThat(registry.configFor("judge").thinkingBudget())
                    .as("the judge is on the request path and must not think")
                    .isZero();
            assertThat(registry.configFor("judge").timeout())
                    .isLessThanOrEqualTo(registry.configFor("agent").timeout());
        }
    }

    @Test
    void buildsOneModelPerConfiguredRoleAcrossProviders() {
        try (var ctx = ApplicationContext.run(with(Map.of(
                "agentic.llm.models.probe-google.provider", "GOOGLE",
                "agentic.llm.models.probe-google.model-name", "gemini-2.5-flash-lite",
                "agentic.llm.models.probe-google.thinking-budget", 0,
                "agentic.llm.models.probe-openai.provider", "OPENAI",
                "agentic.llm.models.probe-openai.model-name", "gpt-4o-mini")))) {

            var registry = ctx.getBean(ChatModelRegistry.class);

            assertThat(registry.roles()).contains("probe-google", "probe-openai");
            assertThat(registry.forRole("probe-google")).isNotSameAs(registry.forRole("probe-openai"));
            assertThat(registry.configFor("probe-openai").modelName()).isEqualTo("gpt-4o-mini");
        }
    }

    @Test
    void anUnknownRoleFailsLoudlyInsteadOfFallingBack() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            assertThatThrownBy(() -> ctx.getBean(ChatModelRegistry.class).forRole("summarizer"))
                    .isInstanceOf(ChatModelRegistry.UnknownModelRoleException.class)
                    .hasMessageContaining("summarizer");
        }
    }

    @Test
    void aMissingApiKeyFailsAtStartupNotAtFirstRequest() {
        try (var ctx = ApplicationContext.run(Map.of(
                "agentic.llm.credentials.google-api-key", "",
                "agentic.llm.credentials.openai-api-key", ""))) {

            assertThatThrownBy(() -> ctx.getBean(ChatModelRegistry.class))
                    .rootCause()
                    .isInstanceOf(ChatModelRegistry.MissingCredentialsException.class)
                    .hasMessageContaining("API key");
        }
    }

    @Test
    void anInvalidThinkingOptionFailsAtStartup() {
        try (var ctx = ApplicationContext.run(with(Map.of(
                "agentic.llm.models.probe-bad.provider", "GOOGLE",
                "agentic.llm.models.probe-bad.model-name", "gemini-3.1-flash-lite",
                "agentic.llm.models.probe-bad.thinking-budget", 0)))) {

            assertThatThrownBy(() -> ctx.getBean(ChatModelRegistry.class))
                    .rootCause()
                    .hasMessageContaining("thinking-level");
        }
    }

    private static Map<String, Object> with(Map<String, Object> extra) {
        var all = new HashMap<String, Object>(CREDENTIALS);
        all.putAll(extra);
        return all;
    }
}
