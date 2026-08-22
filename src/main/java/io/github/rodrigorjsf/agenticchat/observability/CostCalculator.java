package io.github.rodrigorjsf.agenticchat.observability;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns token counts into money.
 *
 * <p>{@link BigDecimal} rather than {@code double}: per-token prices are around
 * 1e-7 USD, and summing millions of those in binary floating point drifts in a way
 * that is invisible until the invoice disagrees with the dashboard.
 */
@Singleton
public class CostCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(CostCalculator.class);
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final Map<String, ModelPrice> pricesByModel;

    public CostCalculator(List<ModelPrice> prices) {
        this.pricesByModel = prices.stream().collect(Collectors.toUnmodifiableMap(
                price -> normalise(price.name()), price -> price));
        LOG.info("Priced models: {}", new java.util.TreeSet<>(pricesByModel.keySet()));
    }

    /**
     * Cost per usage bucket, keyed exactly like {@link TokenUsageDetails#buckets()}
     * plus a {@code total}.
     *
     * <p>Langfuse can work cost out for itself from its own model definitions. This
     * application ingests the number instead, from {@code agentic.llm.pricing}, so the
     * trace and the Prometheus counter cannot disagree about the same call — the same
     * argument that put those prices in configuration rather than in a constant.
     *
     * @return an EMPTY map when the model has no configured price. Empty rather than
     * zero: a confident zero is exactly the confidently-wrong report this project
     * refuses to produce, and an absent cost is visible in the UI where a zero is not.
     */
    public Map<String, BigDecimal> breakdownOf(String modelName, TokenUsageDetails usage) {
        var price = pricesByModel.get(normalise(modelName));
        if (price == null || usage.isEmpty()) {
            return Map.of();
        }
        var breakdown = new LinkedHashMap<String, BigDecimal>();
        add(breakdown, TokenUsageDetails.INPUT, usage.inputTokens(), price.inputPerMillion());
        add(breakdown, TokenUsageDetails.INPUT_CACHED, usage.cachedInputTokens(), price.cachedInputPerMillion());
        add(breakdown, TokenUsageDetails.OUTPUT, usage.outputTokens(), price.outputPerMillion());
        // Reasoning tokens are billed at the output rate by both providers.
        add(breakdown, TokenUsageDetails.OUTPUT_REASONING, usage.reasoningOutputTokens(), price.outputPerMillion());
        if (breakdown.isEmpty()) {
            return Map.of();
        }
        breakdown.put(TokenUsageDetails.TOTAL, breakdown.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return Map.copyOf(breakdown);
    }

    /**
     * @return USD for one model call, {@link BigDecimal#ZERO} when the model has no
     * configured price
     */
    public BigDecimal costOf(String modelName, TokenUsageDetails usage) {
        return breakdownOf(modelName, usage).getOrDefault(TokenUsageDetails.TOTAL, BigDecimal.ZERO);
    }

    private static void add(Map<String, BigDecimal> breakdown, String bucket, long tokens, BigDecimal perMillion) {
        if (tokens > 0) {
            breakdown.put(bucket, perMillion.multiply(BigDecimal.valueOf(tokens))
                    .divide(MILLION, MathContext.DECIMAL64));
        }
    }

    /**
     * @return USD for one model call, or {@link BigDecimal#ZERO} when the model has
     * no configured price
     */
    public BigDecimal costOf(String modelName, long inputTokens, long outputTokens, long cachedInputTokens) {
        var price = pricesByModel.get(normalise(modelName));
        if (price == null) {
            return BigDecimal.ZERO;
        }
        long freshInput = Math.max(0, inputTokens - cachedInputTokens);
        return price.inputPerMillion().multiply(BigDecimal.valueOf(freshInput))
                .add(price.cachedInputPerMillion().multiply(BigDecimal.valueOf(cachedInputTokens)))
                .add(price.outputPerMillion().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, MathContext.DECIMAL64);
    }

    public boolean isPriced(String modelName) {
        return modelName != null && pricesByModel.containsKey(normalise(modelName));
    }

    /**
     * Property keys cannot contain dots, so the config writes them as hyphens.
     */
    private static String normalise(String modelName) {
        return modelName == null ? "" : modelName.toLowerCase(Locale.ROOT).replace('.', '-');
    }
}
