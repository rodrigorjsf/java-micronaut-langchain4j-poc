package io.github.rodrigorjsf.agenticchat.observability;

import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CostAccountingTest {

    private static final Map<String, Object> CREDENTIALS = Map.of(
            "agentic.llm.credentials.google-api-key", "fake",
            "agentic.llm.credentials.openai-api-key", "fake");

    @Test
    @DisplayName("prices come from configuration, keyed with hyphens for dots")
    void pricesTheShippedModels() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var costs = ctx.getBean(CostCalculator.class);

            assertThat(costs.isPriced("gemini-2.5-flash-lite")).isTrue();
            assertThat(costs.isPriced("gpt-4o-mini")).isTrue();

            // 1M input at $0.10 + 1M output at $0.40, no cache hit.
            assertThat(costs.costOf("gemini-2.5-flash-lite", 1_000_000, 1_000_000, 0))
                    .isEqualByComparingTo(new BigDecimal("0.50"));
        }
    }

    @Test
    @DisplayName("cached input tokens are billed at the cached rate, not the full one")
    void cachedTokensAreDiscounted() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var costs = ctx.getBean(CostCalculator.class);

            var uncached = costs.costOf("gemini-2.5-flash-lite", 1_000_000, 0, 0);
            var allCached = costs.costOf("gemini-2.5-flash-lite", 1_000_000, 0, 1_000_000);

            assertThat(uncached).isEqualByComparingTo(new BigDecimal("0.10"));
            assertThat(allCached).isEqualByComparingTo(new BigDecimal("0.01"));
            assertThat(allCached).isLessThan(uncached);
        }
    }

    @Test
    @DisplayName("an unpriced model reports zero rather than guessing")
    void anUnpricedModelIsZeroAndFlagged() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var costs = ctx.getBean(CostCalculator.class);

            assertThat(costs.isPriced("some-model-nobody-configured")).isFalse();
            assertThat(costs.costOf("some-model-nobody-configured", 1_000, 1_000, 0))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("a real turn emits per-role token and cost metrics")
    void costIsAttributedPerRole() {
        // Without a per-role tag the LLM bill is one number, and the claim that the
        // judge is most of the calls and a small part of the cost is unprovable.
        var config = new java.util.HashMap<String, Object>(CREDENTIALS);
        config.put("agentic.test.stub-models", "true");
        config.put("agentic.guardrails.input.llm-classifier-enabled", "false");

        try (var ctx = ApplicationContext.run(config)) {
            var models = (io.github.rodrigorjsf.agenticchat.testsupport.StubChatModelRegistry)
                    ctx.getBean(ChatModelRegistry.class);
            models.model("judge").replyWith("""
                    {"decision":"IN_SCOPE","confidence":0.9,"intent":"chat","language":"pt-BR",
                     "skillHint":"","riskFlags":[],"outOfScopeReply":""}""");
            models.model("agent").replyWith("resposta");

            ctx.getBean(io.github.rodrigorjsf.agenticchat.conversation.ChatTurnService.class)
                    .handle(io.github.rodrigorjsf.agenticchat.memory.ConversationId.newId(),
                            "uma pergunta qualquer sobre o clima");

            var meters = ctx.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            for (String role : new String[]{"judge", "agent"}) {
                var counter = meters.find("agentic.llm.tokens")
                        .tag("role", role).tag("kind", "input").counter();
                assertThat(counter)
                        .as("no input-token counter for role %s", role)
                        .isNotNull();
                assertThat(counter.count()).isPositive();
            }
        }
    }

    @Test
    void aPartlyCachedCallIsBilledOnBothRates() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var costs = ctx.getBean(CostCalculator.class);

            // 600k fresh at $0.10/M + 400k cached at $0.01/M = 0.06 + 0.004
            assertThat(costs.costOf("gemini-2.5-flash-lite", 1_000_000, 0, 400_000))
                    .isEqualByComparingTo(new BigDecimal("0.064"));
        }
    }

    @Test
    void cachedTokensAreNeverCountedTwiceEvenIfTheProviderOverReports() {
        try (var ctx = ApplicationContext.run(CREDENTIALS)) {
            var costs = ctx.getBean(CostCalculator.class);

            // cached > input would make the fresh count negative if it were not clamped.
            assertThat(costs.costOf("gemini-2.5-flash-lite", 100, 0, 1_000))
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }
}
