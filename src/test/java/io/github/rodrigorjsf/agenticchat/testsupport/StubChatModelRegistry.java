package io.github.rodrigorjsf.agenticchat.testsupport;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.llm.config.ProviderCredentials;
import io.github.rodrigorjsf.agenticchat.observability.CostCalculator;
import io.github.rodrigorjsf.agenticchat.observability.TokenCostListener;
import io.github.rodrigorjsf.agenticchat.observability.trace.AgentTracer;
import io.github.rodrigorjsf.agenticchat.observability.trace.LangfuseChatModelListener;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationContentPolicy;
import io.micrometer.core.instrument.MeterRegistry;
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
 * separately from what the agent was asked — the distinction the entire triage
 * design rests on.
 *
 * <p>Each scripted model carries the same listeners the real registry attaches —
 * {@link TokenCostListener} and {@link LangfuseChatModelListener}. Without them the
 * double would be faithful about requests and silently unfaithful about accounting and
 * about tracing, and both would be exercised by nothing.
 */
@Singleton
@Replaces(ChatModelRegistry.class)
@Requires(property = "agentic.test.stub-models", value = "true")
public class StubChatModelRegistry extends ChatModelRegistry {

    private static final ProviderCredentials STUB_CREDENTIALS = new ProviderCredentials() {
        @Override
        public String googleApiKey() {
            return "stub";
        }

        @Override
        public String openaiApiKey() {
            return "stub";
        }
    };

    private final Map<String, ScriptedChatModel> models = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final CostCalculator costs;
    private final AgentTracer tracer;
    private final ObservationContentPolicy content;

    public StubChatModelRegistry(MeterRegistry meters,
                                 CostCalculator costs,
                                 AgentTracer tracer,
                                 ObservationContentPolicy content) {
        super(List.of(), STUB_CREDENTIALS, List.of(), meters, costs, tracer, content);
        this.meters = meters;
        this.costs = costs;
        this.tracer = tracer;
        this.content = content;
    }

    public ScriptedChatModel model(String role) {
        return models.computeIfAbsent(role, name ->
                new ScriptedChatModel().withListeners(listenersFor(name)));
    }

    private List<ChatModelListener> listenersFor(String role) {
        return List.of(
                new TokenCostListener(role, meters, costs),
                new LangfuseChatModelListener(role, tracer, costs, content));
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
