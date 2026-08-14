package io.github.rodrigorjsf.agenticchat.observability;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
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
     * @return USD for one model call, or {@link BigDecimal#ZERO} when the model has
     *         no configured price
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

    /** Property keys cannot contain dots, so the config writes them as hyphens. */
    private static String normalise(String modelName) {
        return modelName == null ? "" : modelName.toLowerCase(Locale.ROOT).replace('.', '-');
    }
}
