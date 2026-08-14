package io.github.rodrigorjsf.agenticchat.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.github.rodrigorjsf.agenticchat.llm.config.ModelProvider;
import io.github.rodrigorjsf.agenticchat.llm.config.ModelRoleProperties;
import io.github.rodrigorjsf.agenticchat.llm.config.ModelRoleValidator;
import io.github.rodrigorjsf.agenticchat.llm.config.ProviderCredentials;
import io.github.rodrigorjsf.agenticchat.observability.CostCalculator;
import io.github.rodrigorjsf.agenticchat.observability.TokenCostListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds one {@link ChatModel} per configured role and hands it out by name.
 *
 * <p>Roles rather than model ids: no class in this codebase names a model, so
 * changing the triage model or moving a role between vendors is a configuration
 * change. It also makes per-role cost and latency measurable, which is the whole
 * point of having a cheap judge in front of an expensive agent.
 *
 * <p>Every model is built eagerly at startup so a bad key, an unknown model name
 * or an invalid thinking option fails the deployment rather than the first user.
 */
@Singleton
public class ChatModelRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ChatModelRegistry.class);

    private final Map<String, ChatModel> modelsByRole;
    private final Map<String, ModelRoleProperties> configByRole;

    public ChatModelRegistry(List<ModelRoleProperties> roles,
                             ProviderCredentials credentials,
                             List<ChatModelListener> listeners) {
        this(roles, credentials, listeners, null, null);
    }

    /**
     * Each role gets its own {@link TokenCostListener}, which is what puts a
     * {@code role} tag on every token and cost metric. Without that tag the LLM bill
     * is one number and there is no way to see that the judge is most of the calls
     * and a small part of the cost — the fact the whole triage design rests on.
     */
    @jakarta.inject.Inject
    public ChatModelRegistry(List<ModelRoleProperties> roles,
                             ProviderCredentials credentials,
                             List<ChatModelListener> listeners,
                             @Nullable MeterRegistry meters,
                             @Nullable CostCalculator costs) {
        this.configByRole = roles.stream()
                .collect(Collectors.toUnmodifiableMap(ModelRoleProperties::name, Function.identity()));

        var models = new java.util.LinkedHashMap<String, ChatModel>();
        for (ModelRoleProperties role : roles) {
            ModelRoleValidator.validate(role);
            var perRole = new java.util.ArrayList<>(listeners);
            if (meters != null && costs != null) {
                perRole.add(new TokenCostListener(role.name(), meters, costs));
            }
            models.put(role.name(), build(role, credentials, perRole));
        }
        this.modelsByRole = Map.copyOf(models);

        modelsByRole.keySet().forEach(role -> LOG.info("LLM role '{}' -> {} {}",
                role, configByRole.get(role).provider(), configByRole.get(role).modelName()));
    }

    /**
     * @throws UnknownModelRoleException if nothing is configured under that role —
     *         a typo in a role name must not silently fall back to another model.
     */
    public ChatModel forRole(String role) {
        var model = modelsByRole.get(role);
        if (model == null) {
            throw new UnknownModelRoleException(
                    "no LLM configured for role '%s'; configured roles: %s".formatted(role, modelsByRole.keySet()));
        }
        return model;
    }

    public ModelRoleProperties configFor(String role) {
        forRole(role);
        return configByRole.get(role);
    }

    public java.util.Set<String> roles() {
        return modelsByRole.keySet();
    }

    private static ChatModel build(ModelRoleProperties role,
                                   ProviderCredentials credentials,
                                   List<ChatModelListener> listeners) {
        if (!credentials.has(role.provider())) {
            throw new MissingCredentialsException(
                    "LLM role '%s' needs a %s API key; set it in the untracked .env"
                            .formatted(role.name(), role.provider()));
        }
        return switch (role.provider()) {
            case GOOGLE -> google(role, credentials, listeners);
            case OPENAI -> openAi(role, credentials, listeners);
        };
    }

    private static ChatModel google(ModelRoleProperties role,
                                    ProviderCredentials credentials,
                                    List<ChatModelListener> listeners) {
        var builder = GoogleAiGeminiChatModel.builder()
                .apiKey(credentials.apiKeyFor(ModelProvider.GOOGLE))
                .modelName(role.modelName())
                .temperature(role.temperature())
                .maxOutputTokens(role.maxOutputTokens())
                .timeout(role.timeout())
                .maxRetries(role.maxRetries())
                .logRequests(role.logRequests())
                .logResponses(role.logResponses())
                .listeners(listeners);

        var thinking = thinkingConfig(role);
        if (thinking != null) {
            builder.thinkingConfig(thinking);
        }
        return builder.build();
    }

    /**
     * Validated upstream, so at most one of the two knobs is set here.
     * {@code null} means "leave the model's default alone".
     */
    private static GeminiThinkingConfig thinkingConfig(ModelRoleProperties role) {
        if (role.thinkingBudget() != null) {
            return GeminiThinkingConfig.builder().thinkingBudget(role.thinkingBudget()).build();
        }
        if (role.thinkingLevel() != null) {
            return GeminiThinkingConfig.builder().thinkingLevel(role.thinkingLevel()).build();
        }
        return null;
    }

    private static ChatModel openAi(ModelRoleProperties role,
                                    ProviderCredentials credentials,
                                    List<ChatModelListener> listeners) {
        return OpenAiChatModel.builder()
                .apiKey(credentials.apiKeyFor(ModelProvider.OPENAI))
                .modelName(role.modelName())
                .temperature(role.temperature())
                .maxCompletionTokens(role.maxOutputTokens())
                .timeout(role.timeout())
                .maxRetries(role.maxRetries())
                .logRequests(role.logRequests())
                .logResponses(role.logResponses())
                .strictJsonSchema(true)
                .listeners(listeners)
                .build();
    }

    public static class UnknownModelRoleException extends RuntimeException {
        public UnknownModelRoleException(String message) {
            super(message);
        }
    }

    public static class MissingCredentialsException extends RuntimeException {
        public MissingCredentialsException(String message) {
            super(message);
        }
    }
}
