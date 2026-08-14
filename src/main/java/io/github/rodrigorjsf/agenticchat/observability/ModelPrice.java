package io.github.rodrigorjsf.agenticchat.observability;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.bind.annotation.Bindable;

import java.math.BigDecimal;

/**
 * USD per million tokens for one model.
 *
 * <p>Prices live in configuration rather than in code because they change without
 * asking, and a stale constant produces a cost report that is confidently wrong —
 * which is worse than no report. The key is the provider's model id, so a
 * misconfigured price is visible next to the model that uses it.
 *
 * <p>A model with no configured price is reported with zero cost and counted in a
 * separate metric, so "we are not pricing this model" is observable rather than
 * silently folded into the total as free.
 */
@EachProperty("agentic.llm.pricing")
public class ModelPrice {

    private final String name;
    private final BigDecimal inputPerMillion;
    private final BigDecimal outputPerMillion;
    private final BigDecimal cachedInputPerMillion;

    @ConfigurationInject
    public ModelPrice(@Parameter String name,
                      @Bindable(defaultValue = "0") BigDecimal inputPerMillion,
                      @Bindable(defaultValue = "0") BigDecimal outputPerMillion,
                      @Bindable(defaultValue = "0") BigDecimal cachedInputPerMillion) {
        this.name = name;
        this.inputPerMillion = inputPerMillion;
        this.outputPerMillion = outputPerMillion;
        this.cachedInputPerMillion = cachedInputPerMillion;
    }

    /**
     * The configuration key. Dots are not usable in a Micronaut property key
     * segment, so {@code gemini-2.5-flash-lite} is written
     * {@code gemini-2-5-flash-lite} and normalised back here.
     */
    public String name() {
        return name;
    }

    public BigDecimal inputPerMillion() {
        return inputPerMillion;
    }

    public BigDecimal outputPerMillion() {
        return outputPerMillion;
    }

    /**
     * Providers discount a cache hit; zero means "not priced separately".
     */
    public BigDecimal cachedInputPerMillion() {
        return cachedInputPerMillion;
    }
}
