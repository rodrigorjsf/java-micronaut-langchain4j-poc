package io.github.rodrigorjsf.agenticchat.llm.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.AccessorsStyle;
import io.micronaut.core.bind.annotation.Bindable;

/**
 * API keys, sourced from the environment and never from a tracked file.
 *
 * <p>{@code application.yml} binds these to {@code ${GOOGLE_API_KEY}} and
 * {@code ${OPENAI_API_KEY}}; Micronaut resolves a placeholder against the raw
 * environment variable name, so the untracked {@code .env} that Docker Compose
 * loads is the single source for both the container and a local run.
 */
@ConfigurationProperties("agentic.llm.credentials")
@AccessorsStyle(readPrefixes = "")
public interface ProviderCredentials {

    @Bindable(defaultValue = "")
    String googleApiKey();

    @Bindable(defaultValue = "")
    String openaiApiKey();

    default boolean has(ModelProvider provider) {
        return !apiKeyFor(provider).isBlank();
    }

    default String apiKeyFor(ModelProvider provider) {
        return switch (provider) {
            case GOOGLE -> googleApiKey();
            case OPENAI -> openaiApiKey();
        };
    }
}
