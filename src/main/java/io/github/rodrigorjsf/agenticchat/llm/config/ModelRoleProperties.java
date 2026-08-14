package io.github.rodrigorjsf.agenticchat.llm.config;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;

import java.time.Duration;

/**
 * One configured model, addressed by role rather than by name.
 *
 * <p>Roles — {@code judge}, {@code agent}, {@code summarizer}, … — exist so the
 * code never hard-codes a model id. Swapping the triage model is a config change,
 * and every role can be measured and priced independently.
 *
 * <p>A class with a {@code @ConfigurationInject} constructor rather than a
 * {@code @ConfigurationProperties} interface: on an {@code @EachProperty}
 * interface the {@code @Parameter} key collides with accessor-based binding and
 * Micronaut looks for a literal {@code …models.judge.name} property.
 *
 * <p>{@code thinkingBudget} and {@code thinkingLevel} both exist because Google
 * changed the knob between model generations, and getting it wrong is expensive
 * rather than loud. See {@link ModelRoleValidator}.
 */
@EachProperty("agentic.llm.models")
public class ModelRoleProperties {

    private final String name;
    private final ModelProvider provider;
    private final String modelName;
    private final double temperature;
    private final int maxOutputTokens;
    private final Duration timeout;
    private final int maxRetries;
    private final Integer thinkingBudget;
    private final String thinkingLevel;
    private final boolean logRequests;
    private final boolean logResponses;

    @ConfigurationInject
    public ModelRoleProperties(
            @Parameter String name,
            @Bindable(defaultValue = "GOOGLE") ModelProvider provider,
            @Nullable String modelName,
            @Bindable(defaultValue = "0.0") double temperature,
            @Bindable(defaultValue = "1024") int maxOutputTokens,
            @Bindable(defaultValue = "PT30S") Duration timeout,
            @Bindable(defaultValue = "2") int maxRetries,
            @Nullable Integer thinkingBudget,
            @Nullable String thinkingLevel,
            @Bindable(defaultValue = "false") boolean logRequests,
            @Bindable(defaultValue = "false") boolean logResponses) {
        this.name = name;
        this.provider = provider;
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.thinkingBudget = thinkingBudget;
        this.thinkingLevel = thinkingLevel;
        this.logRequests = logRequests;
        this.logResponses = logResponses;
    }

    /**
     * The role key, e.g. {@code judge}.
     */
    public String name() {
        return name;
    }

    public ModelProvider provider() {
        return provider;
    }

    public String modelName() {
        return modelName;
    }

    public double temperature() {
        return temperature;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Gemini 2.x thinking control. {@code 0} disables thinking.
     * Not accepted by Gemini 3.x models — use {@link #thinkingLevel()} there.
     */
    @Nullable
    public Integer thinkingBudget() {
        return thinkingBudget;
    }

    /**
     * Gemini 3.x thinking control: {@code minimal}, {@code low}, {@code medium}, {@code high}.
     * Ignored by Gemini 2.x models.
     */
    @Nullable
    public String thinkingLevel() {
        return thinkingLevel;
    }

    /**
     * Log full prompts and completions. Off by default — request bodies contain
     * user text, retrieved documents and any prompt-injection payload aimed at us.
     */
    public boolean logRequests() {
        return logRequests;
    }

    public boolean logResponses() {
        return logResponses;
    }
}
