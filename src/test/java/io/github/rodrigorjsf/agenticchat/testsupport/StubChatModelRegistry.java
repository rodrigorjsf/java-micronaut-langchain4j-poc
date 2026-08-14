package io.github.rodrigorjsf.agenticchat.testsupport;

import dev.langchain4j.model.chat.ChatModel;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces the real registry so the whole pipeline can be exercised without a
 * provider, an API key or a network.
 *
 * <p>One scripted model per role, so a test can assert what the judge was asked
 * separately from what the agent was asked — which is the distinction the entire
 * triage design rests on.
 */
@Singleton
@Replaces(ChatModelRegistry.class)
@Requires(property = "agentic.test.stub-models", value = "true")
public class StubChatModelRegistry extends ChatModelRegistry {

    private final Map<String, ScriptedChatModel> models = new ConcurrentHashMap<>();

    public StubChatModelRegistry() {
        super(List.of(), new io.github.rodrigorjsf.agenticchat.llm.config.ProviderCredentials() {
            @Override
            public String googleApiKey() {
                return "stub";
            }

            @Override
            public String openaiApiKey() {
                return "stub";
            }
        }, List.of());
    }

    public ScriptedChatModel model(String role) {
        return models.computeIfAbsent(role, ignored -> new ScriptedChatModel());
    }

    @Override
    public ChatModel forRole(String role) {
        return model(role);
    }

    @Override
    public Set<String> roles() {
        return models.keySet();
    }
}
