package io.github.rodrigorjsf.agenticchat.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
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
        String requestModel = modelNameOf(context.chatRequest() == null
                ? null : context.chatRequest().modelName());
        meters.counter("agentic.llm.errors",
                "role", role,
                "model", requestModel,
                "exception", context.error().getClass().getSimpleName()).increment();
    }

    private void record(ChatModelResponseContext context) {
        var response = context.chatResponse();
        var usage = response == null ? null : response.tokenUsage();
        String model = modelNameOf(response == null ? null : response.modelName());

        // ONE computation, several emitters. LangfuseChatModelListener writes these same
        // buckets into langfuse.observation.usage_details and prices them with this same
        // call, so the Prometheus counter and the Langfuse span are two views of one
        // number rather than two opinions about it.
        //
        // This used to read the raw counts instead, and the two DID disagree: the 4-argument
        // costOf has no notion of a reasoning token, so a Gemini answer that spent 880
        // tokens thinking — which Google bills at the output rate and reports BESIDE
        // candidatesTokenCount — was costed at 1.8e-4 here and 5.32e-4 on the span. The
        // agent role runs with thinking-level: low, so that was every turn.
        var details = TokenUsageDetails.of(usage);

        // The buckets are mutually exclusive, which is what makes `sum by (kind)` the true
        // total and what the dashboard's cache-hit panel already assumes: its denominator
        // is kind=~"input|cached_input", and that is only a total if the two are disjoint.
        // TOTAL is skipped for the same reason — it is the provider's own sum, and
        // counting it beside its parts would double every rate on the panel.
        details.buckets().forEach((kind, count) -> {
            if (!TokenUsageDetails.TOTAL.equals(kind)) {
                meters.counter("agentic.llm.tokens", "role", role, "model", model, "kind", kind)
                        .increment(count);
            }
        });

        var cost = costs.costOf(model, details);
        meters.counter("agentic.llm.cost_usd", "role", role, "model", model)
                .increment(cost.doubleValue());
        if (!costs.isPriced(model)) {
            meters.counter("agentic.llm.unpriced_calls", "role", role, "model", model).increment();
        }

        var elapsed = elapsed(context.attributes());
        if (elapsed != null) {
            meters.timer("agentic.llm.latency", "role", role, "model", model).record(elapsed);
        }

        LOG.debug("LLM call role={} model={} tokens={} usd={}",
                role, model, details.buckets(), cost.toPlainString());
    }

    private static String modelNameOf(String modelName) {
        return modelName == null || modelName.isBlank() ? "unknown" : modelName;
    }

    private static java.time.Duration elapsed(java.util.Map<Object, Object> attributes) {
        Object start = attributes == null ? null : attributes.get(START_TIME);
        return start instanceof Long startNanos
                ? java.time.Duration.ofNanos(System.nanoTime() - startNanos)
                : null;
    }
}
