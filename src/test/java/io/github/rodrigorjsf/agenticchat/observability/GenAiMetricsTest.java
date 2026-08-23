package io.github.rodrigorjsf.agenticchat.observability;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-model-call accounting, asserted where the two readers of it can disagree.
 *
 * <p>The same model call is measured twice on purpose — once as Micrometer counters that
 * Prometheus scrapes, once as {@code langfuse.observation.cost_details} on the generation
 * span. The point of this class is that those two numbers are derived from ONE computation
 * and therefore cannot drift: a dashboard that says the month bill is $12 and a Langfuse trace
 * that says $14 is worse than having neither, because the discrepancy is invisible until
 * someone reconciles them by hand.
 *
 * <p>Gemini is the case that exposes it. {@code candidatesTokenCount} does not include
 * {@code thoughtsTokenCount}, Google bills the thoughts at the output rate, and the agent
 * role runs with {@code thinking-level: low} — so a cost computed from the reported output
 * alone understates every single turn.
 */
class GenAiMetricsTest {

    private static final String GEMINI = "gemini-2.5-flash-lite";
    private static final Map<String, Object> CREDENTIALS = Map.of(
            "agentic.llm.credentials.google-api-key", "fake",
            "agentic.llm.credentials.openai-api-key", "fake");

    private ApplicationContext ctx;
    private CostCalculator costs;
    private MeterRegistry meters;

    @BeforeEach
    void setUp() {
        ctx = ApplicationContext.run(new HashMap<>(CREDENTIALS));
        costs = ctx.getBean(CostCalculator.class);
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    /**
     * A Gemini response that spent 880 tokens thinking. Google reports those BESIDE the
     * candidate tokens, not inside them.
     */
    private static GoogleAiGeminiTokenUsage geminiWithThoughts() {
        return GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(1_000)
                .outputTokenCount(200)
                .thoughtsTokenCount(880)
                .totalTokenCount(2_080)
                .build();
    }

    private void oneCall(GoogleAiGeminiTokenUsage usage) {
        var listener = new TokenCostListener("agent", meters, costs);
        var request = ChatRequest.builder().messages(UserMessage.from("bom dia")).modelName(GEMINI).build();
        var attributes = new java.util.concurrent.ConcurrentHashMap<Object, Object>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.GOOGLE_AI_GEMINI, attributes));
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("Bom dia!"))
                        .modelName(GEMINI)
                        .tokenUsage(usage)
                        .build(),
                request, ModelProvider.GOOGLE_AI_GEMINI, attributes));
    }

    private double counter(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    @Test
    @DisplayName("the Micrometer cost is the SAME number the Langfuse span carries")
    void theTwoCostsAgree() {
        var usage = geminiWithThoughts();
        oneCall(usage);

        // The right-hand side is exactly what LangfuseChatModelListener writes into
        // cost_details. Asserting equality rather than a literal is deliberate: a price
        // change in application.yml must move both or fail here, and a literal would let
        // one of them drift while this test kept passing.
        var langfuseCost = costs.costOf(GEMINI, TokenUsageDetails.of(usage));

        assertThat(counter("agentic.llm.cost_usd", "role", "agent", "model", GEMINI))
                .isEqualTo(langfuseCost.doubleValue());
    }

    @Test
    @DisplayName("reasoning tokens are counted, at the output rate, in their own bucket")
    void reasoningTokensAreCounted() {
        oneCall(geminiWithThoughts());

        // Its own kind rather than folded into output: the two are billed alike and
        // controlled differently. `thinking-level` is a knob, and a bucket that hides its
        // effect makes the knob unmeasurable.
        assertThat(counter("agentic.llm.tokens", "role", "agent", "model", GEMINI,
                "kind", TokenUsageDetails.OUTPUT_REASONING)).isEqualTo(880);
    }

    @Test
    @DisplayName("a response with no thoughts records no reasoning bucket at all")
    void noThoughtsMeansNoReasoningBucket() {
        oneCall(GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(1_000)
                .outputTokenCount(200)
                .totalTokenCount(1_200)
                .build());

        // Absent, not zero. A confident zero claims the model was asked to think and did
        // not; absence says the question was never put.
        assertThat(meters.find("agentic.llm.tokens")
                .tags("role", "agent", "model", GEMINI, "kind", TokenUsageDetails.OUTPUT_REASONING)
                .counter()).isNull();
    }

}
