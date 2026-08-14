package io.github.rodrigorjsf.agenticchat.guardrail.input;

import dev.langchain4j.service.AiServices;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the gray-zone classifier.
 *
 * <p>The pass-through variant exists so the deterministic rules can be exercised in
 * tests, in CI and in a degraded deployment without a model. It is a real
 * configuration, not a mock: with it, the gray zone simply passes, which is the
 * same fail-open posture the rest of the pipeline takes.
 */
@Factory
public class InjectionClassifierFactory {

    private static final Logger LOG = LoggerFactory.getLogger(InjectionClassifierFactory.class);

    @Singleton
    InjectionClassifier injectionClassifier(
            ChatModelRegistry models,
            @Value("${agentic.guardrails.input.llm-classifier-enabled:true}") boolean enabled) {

        if (!enabled) {
            LOG.warn("Gray-zone injection classifier is disabled; only deterministic rules apply");
            return text -> InjectionVerdict.benign("classifier disabled");
        }

        var judge = AiServices.builder(InjectionJudge.class)
                .chatModel(models.forRole("judge"))
                .build();

        return text -> {
            try {
                return judge.classify(text);
            } catch (RuntimeException e) {
                // Fail open, and say so loudly. Blocking every gray-zone turn because
                // the model is rate-limited would be a self-inflicted outage; the
                // deterministic rules have already cleared this text of anything
                // structurally certain.
                LOG.warn("Injection classifier unavailable; gray-zone turn allowed through", e);
                return InjectionVerdict.benign("classifier unavailable");
            }
        };
    }
}
