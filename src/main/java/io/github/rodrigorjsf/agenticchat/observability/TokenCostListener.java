package io.github.rodrigorjsf.agenticchat.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Per-model-call accounting: tokens, cache hits, money and latency.
 *
 * <p>One instance per configured role, so every metric carries a {@code role} tag.
 * That is the whole reason roles exist: without it, "the LLM bill" is one number
 * and there is no way to see that the judge is 90% of the calls and 5% of the
 * cost, which is exactly the fact the triage design is built on.
 *
 * <p>Cached input tokens are read separately where the provider reports them —
 * {@code OpenAiTokenUsage.inputTokensDetails().cachedTokens()} and
 * {@code GoogleAiGeminiTokenUsage.cachedContentTokenCount()}. A cache-hit rate
 * that is not measured is a cache-hit rate nobody can claim, and the prompt is
 * laid out specifically to earn those hits.
 *
 * <p>Nothing here throws. LangChain4j swallows listener exceptions, so a failure
 * would be an invisible hole in the accounting rather than a loud error — the
 * catch is explicit for that reason.
 */
public class TokenCostListener implements ChatModelListener {

    private static final Logger LOG = LoggerFactory.getLogger(TokenCostListener.class);
    private static final String START_TIME = "agentic.start.nanos";

    private final String role;
    private final MeterRegistry meters;
    private final CostCalculator costs;

    public TokenCostListener(String role, MeterRegistry meters, CostCalculator costs) {
        this.role = role;
        this.meters = meters;
        this.costs = costs;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(START_TIME, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        try {
            record(context);
        } catch (RuntimeException e) {
            // LangChain4j swallows listener exceptions; log rather than lose the reason.
            LOG.warn("Token accounting failed for role {}", role, e);
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        meters.counter("agentic.llm.errors",
                "role", role,
                "model", modelNameOf(context.chatRequest() == null ? null : context.chatRequest().modelName()),
                "exception", context.error().getClass().getSimpleName()).increment();
    }

    private void record(ChatModelResponseContext context) {
        var response = context.chatResponse();
        var usage = response == null ? null : response.tokenUsage();
        String model = modelNameOf(response == null ? null : response.modelName());

        long input = value(usage == null ? null : usage.inputTokenCount());
        long output = value(usage == null ? null : usage.outputTokenCount());
        long cached = cachedTokens(usage);

        meters.counter("agentic.llm.tokens", "role", role, "model", model, "kind", "input")
                .increment(input);
        meters.counter("agentic.llm.tokens", "role", role, "model", model, "kind", "output")
                .increment(output);
        if (cached > 0) {
            meters.counter("agentic.llm.tokens", "role", role, "model", model, "kind", "cached_input")
                    .increment(cached);
        }

        var cost = costs.costOf(model, input, output, cached);
        meters.counter("agentic.llm.cost_usd", "role", role, "model", model)
                .increment(cost.doubleValue());
        if (!costs.isPriced(model)) {
            meters.counter("agentic.llm.unpriced_calls", "role", role, "model", model).increment();
        }

        Object start = context.attributes().get(START_TIME);
        if (start instanceof Long startNanos) {
            meters.timer("agentic.llm.latency", "role", role, "model", model)
                    .record(java.time.Duration.ofNanos(System.nanoTime() - startNanos));
        }

        LOG.debug("LLM call role={} model={} in={} out={} cached={} usd={}",
                role, model, input, output, cached, cost.toPlainString());
    }

    /**
     * Provider-specific, because the base {@code TokenUsage} has no notion of a cache
     * hit. Reflection-free: both subclasses are on the compile classpath already.
     */
    private static long cachedTokens(dev.langchain4j.model.output.TokenUsage usage) {
        if (usage instanceof OpenAiTokenUsage openAi) {
            var details = openAi.inputTokensDetails();
            return details == null ? 0 : value(details.cachedTokens());
        }
        if (usage instanceof GoogleAiGeminiTokenUsage gemini) {
            return value(gemini.cachedContentTokenCount());
        }
        return 0;
    }

    private static long value(Integer count) {
        return count == null ? 0 : count;
    }

    private static String modelNameOf(String modelName) {
        return modelName == null || modelName.isBlank() ? "unknown" : modelName;
    }
}
