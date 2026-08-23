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
import io.github.rodrigorjsf.agenticchat.observability.trace.AgentTracer;
import io.github.rodrigorjsf.agenticchat.observability.trace.LangfuseChatModelListener;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationContentPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Each role gets its own {@link TokenCostListener} and its own
     * {@link LangfuseChatModelListener}, which is what puts a {@code role} tag on every
     * token and cost metric and a role name on every generation observation. Without it the
     * LLM bill is one number and there is no way to see that the judge is most of the calls
     * and a small part of the cost — the fact the whole triage design rests on.
     *
     * <p>One constructor, and no nullable dependencies. The convenience overload that used
     * to exist took four nulls, which made "tracing is on" a conjunction of three
     * not-null checks: a deployment with a tracer and no {@code CostCalculator} would have
     * silently produced no generation observation at all, for a reason nothing reported.
     * Every one of these beans is unconditional, so the only caller that wanted the short
     * form was a test double, and it can pass what it has.
     */
    public ChatModelRegistry(List<ModelRoleProperties> roles,
                             ProviderCredentials credentials,
                             List<ChatModelListener> listeners,
                             MeterRegistry meters,
                             CostCalculator costs,
                             AgentTracer tracer,
                             ObservationContentPolicy content) {
        this.configByRole = roles.stream()
                .collect(Collectors.toUnmodifiableMap(ModelRoleProperties::name, Function.identity()));

        var models = new LinkedHashMap<String, ChatModel>();
        for (ModelRoleProperties role : roles) {
            ModelRoleValidator.validate(role);
            var perRole = new ArrayList<>(listeners);
            perRole.add(new TokenCostListener(role.name(), meters, costs));
            // Per role for the same reason the cost listener is: the observation is named
            // for the role, so the judge's generations and the agent's are separable in a
            // trace without anyone having to recognise a model id.
            perRole.add(new LangfuseChatModelListener(role.name(), tracer, costs, content));
            models.put(role.name(), build(role, credentials, perRole));
        }
        this.modelsByRole = Map.copyOf(models);

        modelsByRole.keySet().forEach(role -> LOG.info("LLM role '{}' -> {} {}",
                role, configByRole.get(role).provider(), configByRole.get(role).modelName()));
    }

    /**
     * @throws UnknownModelRoleException if nothing is configured under that role —
     *                                   a typo in a role name must not silently fall back to another model.
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

    public Set<String> roles() {
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
