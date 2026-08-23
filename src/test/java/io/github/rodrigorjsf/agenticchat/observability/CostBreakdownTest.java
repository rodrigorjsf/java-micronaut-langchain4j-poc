package io.github.rodrigorjsf.agenticchat.observability;

import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cost, decomposed per usage bucket.
 *
 * <p>Langfuse can infer cost itself from its own model definitions. This project ingests
 * it instead, from the prices in {@code agentic.llm.pricing}, so the trace and the
 * Prometheus counter cannot report two different numbers for the same call — the same
 * reason those prices are configuration rather than a constant.
 */
class CostBreakdownTest {

    private static final Map<String, Object> CREDENTIALS = Map.of(
            "agentic.llm.credentials.google-api-key", "fake",
            "agentic.llm.credentials.openai-api-key", "fake");

    private static TokenUsageDetails gemini(long input, long cached, long output, long thoughts) {
        return TokenUsageDetails.of(GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount((int) input)
                .cachedContentTokenCount((int) cached)
                .outputTokenCount((int) output)
                .thoughtsTokenCount((int) thoughts)
                .build());
    }

    @Test
    @DisplayName("the breakdown names the same buckets the usage details do")
    void breakdownIsKeyedByUsageBucket() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var costs = ctx.getBean(CostCalculator.class);

            // 600k fresh input at $0.10/M, 400k cached at $0.01/M, 1M output at $0.40/M.
            var breakdown = costs.breakdownOf("gemini-2.5-flash-lite", gemini(1_000_000, 400_000, 1_000_000, 0));

            assertThat(breakdown.get("input")).isEqualByComparingTo(new BigDecimal("0.06"));
            assertThat(breakdown.get("input_cached_tokens")).isEqualByComparingTo(new BigDecimal("0.004"));
            assertThat(breakdown.get("output")).isEqualByComparingTo(new BigDecimal("0.40"));
            assertThat(breakdown.get("total")).isEqualByComparingTo(new BigDecimal("0.464"));
        }
    }

    @Test
    @DisplayName("reasoning tokens are billed at the output rate rather than being free")
    void reasoningTokensAreBilled() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var costs = ctx.getBean(CostCalculator.class);

            var breakdown = costs.breakdownOf("gemini-2.5-flash-lite", gemini(0, 0, 0, 1_000_000));

            assertThat(breakdown.get("output_reasoning_tokens")).isEqualByComparingTo(new BigDecimal("0.40"));
            assertThat(breakdown.get("total")).isEqualByComparingTo(new BigDecimal("0.40"));
        }
    }

    @Test
    @DisplayName("the breakdown total is the number the existing metric reports")
    void theBreakdownAgreesWithCostOf() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var costs = ctx.getBean(CostCalculator.class);

            var details = TokenUsageDetails.of(new TokenUsage(12_345, 678));
            assertThat(costs.breakdownOf("gpt-4o-mini", details).get("total"))
                    .isEqualByComparingTo(costs.costOf("gpt-4o-mini", 12_345, 678, 0));
        }
    }

    @Test
    @DisplayName("an unpriced model yields no breakdown rather than a confident zero")
    void anUnpricedModelHasNoBreakdown() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var costs = ctx.getBean(CostCalculator.class);

            assertThat(costs.breakdownOf("llama-99b", TokenUsageDetails.of(new TokenUsage(100, 100)))).isEmpty();
        }
    }

    @Test
    @DisplayName("a bucket with no tokens is left out, not written as zero")
    void emptyBucketsAreOmitted() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var costs = ctx.getBean(CostCalculator.class);

            assertThat(costs.breakdownOf("gpt-4o-mini", TokenUsageDetails.of(new TokenUsage(1_000, 500))))
                    .containsKeys("input", "output", "total")
                    .doesNotContainKey("input_cached_tokens");
        }
    }
}
