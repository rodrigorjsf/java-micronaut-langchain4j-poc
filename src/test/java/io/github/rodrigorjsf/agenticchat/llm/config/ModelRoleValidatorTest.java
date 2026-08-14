package io.github.rodrigorjsf.agenticchat.llm.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRoleValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"gemini-3.1-flash-lite", "gemini-3-flash-preview", "gemini-3.5-flash-lite", "gemini-3.7-flash"})
    void rejectsThinkingBudgetOnGemini3(String modelName) {
        assertThatThrownBy(() -> ModelRoleValidator.validate(
                role("judge", ModelProvider.GOOGLE, modelName, 0, null)))
                .isInstanceOf(ModelRoleValidator.InvalidModelConfigurationException.class)
                .hasMessageContaining("thinking-level");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gemini-2.5-flash-lite", "gemini-2.5-flash"})
    void rejectsThinkingLevelOnGemini2(String modelName) {
        assertThatThrownBy(() -> ModelRoleValidator.validate(
                role("judge", ModelProvider.GOOGLE, modelName, null, "low")))
                .isInstanceOf(ModelRoleValidator.InvalidModelConfigurationException.class)
                .hasMessageContaining("thinking-budget");
    }

    @Test
    void acceptsThinkingBudgetOnGemini2() {
        assertThatCode(() -> ModelRoleValidator.validate(
                role("judge", ModelProvider.GOOGLE, "gemini-2.5-flash-lite", 0, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsThinkingLevelOnGemini3() {
        assertThatCode(() -> ModelRoleValidator.validate(
                role("agent", ModelProvider.GOOGLE, "gemini-3.1-flash-lite", null, "low")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAGeminiModelWithNoThinkingOptionAtAll() {
        assertThatCode(() -> ModelRoleValidator.validate(
                role("agent", ModelProvider.GOOGLE, "gemini-3.1-flash-lite", null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresNonGeminiGoogleModelNames() {
        assertThatCode(() -> ModelRoleValidator.validate(
                role("agent", ModelProvider.GOOGLE, "gemma-4-31b-it", null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsThinkingOptionsOnNonGoogleProviders() {
        assertThatThrownBy(() -> ModelRoleValidator.validate(
                role("judge", ModelProvider.OPENAI, "gpt-4o-mini", 0, null)))
                .isInstanceOf(ModelRoleValidator.InvalidModelConfigurationException.class)
                .hasMessageContaining("GOOGLE");
    }

    @Test
    void acceptsAPlainOpenAiRole() {
        assertThatCode(() -> ModelRoleValidator.validate(
                role("judge", ModelProvider.OPENAI, "gpt-4o-mini", null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresAModelName() {
        assertThatThrownBy(() -> ModelRoleValidator.validate(
                role("judge", ModelProvider.GOOGLE, "  ", null, null)))
                .isInstanceOf(ModelRoleValidator.InvalidModelConfigurationException.class)
                .hasMessageContaining("model-name");
    }

    private static ModelRoleProperties role(String name,
                                            ModelProvider provider,
                                            String modelName,
                                            Integer thinkingBudget,
                                            String thinkingLevel) {
        return new ModelRoleProperties(name, provider, modelName, 0.0, 256,
                Duration.ofSeconds(8), 2, thinkingBudget, thinkingLevel, false, false);
    }
}
