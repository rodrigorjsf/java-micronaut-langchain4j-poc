package io.github.rodrigorjsf.agenticchat.evals.scenario;

import dev.langchain4j.model.output.TokenUsage;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.Cost;
import io.github.rodrigorjsf.agenticchat.observability.CostCalculator;
import io.github.rodrigorjsf.agenticchat.observability.ModelPrice;
import io.github.rodrigorjsf.agenticchat.observability.TokenUsageDetails;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationType;
import io.github.rodrigorjsf.agenticchat.testsupport.RecordingAgentTracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioCostTest {

    /** 1M input tokens at $0.25 and 1M output tokens at $1.50: a price whose totals are easy to read. */
    private static final CostCalculator PRICES = new CostCalculator(List.of(new ModelPrice(
            "gemini-3-1-flash-lite", new BigDecimal("0.25"), new BigDecimal("1.50"), new BigDecimal("0.025"))));

    @Test
    @DisplayName("the estimate sums the cost the application's CostCalculator put on each model call")
    void sumsTheCostEachModelCallCarries() {
        var tracer = new RecordingAgentTracer();
        generation(tracer, "gemini-3.1-flash-lite", usage(1_000_000, 0));
        generation(tracer, "gemini-3.1-flash-lite", usage(0, 1_000_000));
        tracer.start("get_weather", ObservationType.TOOL).close();

        var cost = Cost.of(tracer.recorded());

        assertThat(cost.usd()).isEqualByComparingTo("1.75");
        assertThat(cost.unpricedCalls()).isZero();
    }

    @Test
    @DisplayName("a model call with no configured price is counted, never priced at zero")
    void countsUnpricedCalls() {
        var tracer = new RecordingAgentTracer();
        generation(tracer, "gemini-3.1-flash-lite", usage(1_000_000, 0));
        generation(tracer, "some-unpriced-model", usage(1_000, 10));

        var cost = Cost.of(tracer.recorded());

        assertThat(cost.usd()).isEqualByComparingTo("0.25");
        assertThat(cost.unpricedCalls()).isEqualTo(1);
    }

    /** What {@code LangfuseChatModelListener} does on a response: usage, then the priced breakdown. */
    private static void generation(RecordingAgentTracer tracer, String model, TokenUsageDetails usage) {
        tracer.start("chat", ObservationType.GENERATION)
                .usage(usage)
                .cost(PRICES.breakdownOf(model, usage))
                .close();
    }

    private static TokenUsageDetails usage(int input, int output) {
        return TokenUsageDetails.of(new TokenUsage(input, output));
    }
}
