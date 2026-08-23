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
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
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
    private InMemoryMetricReader recordedMetrics;
    private SdkMeterProvider meterProvider;
    private GenAiMetrics genAi;

    @BeforeEach
    void setUp() {
        ctx = ApplicationContext.run(new HashMap<>(CREDENTIALS));
        costs = ctx.getBean(CostCalculator.class);
        meters = new SimpleMeterRegistry();
        recordedMetrics = InMemoryMetricReader.create();
        meterProvider = SdkMeterProvider.builder().registerMetricReader(recordedMetrics).build();
        OpenTelemetry sdk = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
        genAi = new GenAiMetrics(sdk);
    }

    @AfterEach
    void tearDown() {
        meterProvider.close();
        ctx.close();
    }

    private MetricData metric(String name) {
        return recordedMetrics.collectAllMetrics().stream()
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metric named " + name + "; recorded: "
                        + recordedMetrics.collectAllMetrics().stream().map(MetricData::getName).toList()));
    }

    /** The sum of every point whose gen_ai.token.type is {@code type}. */
    private double tokensOfType(String type) {
        return metric("gen_ai.client.token.usage").getHistogramData().getPoints().stream()
                .filter(point -> type.equals(
                        point.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.token.type"))))
                .mapToDouble(io.opentelemetry.sdk.metrics.data.HistogramPointData::getSum)
                .sum();
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
        var listener = new TokenCostListener("agent", meters, costs, genAi);
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

    @Test
    @DisplayName("gen_ai.client.token.usage folds the exclusive buckets into the two types the convention allows")
    void tokenUsageHistogramFoldsIntoInputAndOutput() {
        var usage = geminiWithThoughts();
        oneCall(usage);

        // gen_ai.token.type is a closed set of `input` and `output`. Langfuse's buckets are
        // four and mutually exclusive, so the fold has to be stated: a cache read is still
        // an input token and a thought is still an output token, and dropping either would
        // make this histogram disagree with usage_details on the same call.
        var details = TokenUsageDetails.of(usage);
        assertThat(tokensOfType("input"))
                .isEqualTo(details.inputTokens() + details.cachedInputTokens());
        assertThat(tokensOfType("output"))
                .isEqualTo(details.outputTokens() + details.reasoningOutputTokens());

        // 880 thoughts are the whole point: an implementation reading outputTokenCount
        // alone passes every other assertion here and loses them.
        assertThat(tokensOfType("output")).isEqualTo(1_080);
        assertThat(tokensOfType("input")).isEqualTo(1_000);
    }

    @Test
    @DisplayName("the token histogram carries the attributes the convention requires")
    void tokenUsageCarriesTheRequiredAttributes() {
        oneCall(geminiWithThoughts());

        var point = metric("gen_ai.client.token.usage").getHistogramData().getPoints().iterator().next();
        var attributes = point.getAttributes().asMap();
        assertThat(attributes.keySet().stream().map(io.opentelemetry.api.common.AttributeKey::getKey))
                .contains("gen_ai.operation.name", "gen_ai.provider.name",
                        "gen_ai.request.model", "gen_ai.response.model", "gen_ai.token.type");
        assertThat(metric("gen_ai.client.token.usage").getUnit()).isEqualTo("{token}");
    }

    @Test
    @DisplayName("gen_ai.client.operation.duration is recorded in SECONDS, not the millis the rest of the stack uses")
    void durationIsInSeconds() {
        oneCall(geminiWithThoughts());

        var duration = metric("gen_ai.client.operation.duration");
        assertThat(duration.getUnit()).isEqualTo("s");
        // A call in this test takes microseconds. Nanoseconds would land in the thousands
        // and milliseconds in the ones, and both would look plausible on a panel — the
        // unit is part of the contract, not a presentation choice.
        assertThat(duration.getHistogramData().getPoints().iterator().next().getSum())
                .isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("both histograms advise the explicit buckets the convention specifies")
    void bucketBoundariesFollowTheConvention() {
        oneCall(geminiWithThoughts());

        // Without the advice the SDK uses its own default boundaries, which top out at
        // 10 000 — every token count above that lands in the overflow bucket and every
        // quantile over it is a guess.
        assertThat(metric("gen_ai.client.token.usage").getHistogramData().getPoints()
                .iterator().next().getBoundaries())
                .isEqualTo(List.of(1d, 4d, 16d, 64d, 256d, 1024d, 4096d, 16384d, 65536d,
                        262144d, 1048576d, 4194304d, 16777216d, 67108864d));
        assertThat(metric("gen_ai.client.operation.duration").getHistogramData().getPoints()
                .iterator().next().getBoundaries())
                .isEqualTo(List.of(0.01d, 0.02d, 0.04d, 0.08d, 0.16d, 0.32d, 0.64d, 1.28d,
                        2.56d, 5.12d, 10.24d, 20.48d, 40.96d, 81.92d));
    }

    @Test
    @DisplayName("a failed call is a duration point carrying error.type, not a missing measurement")
    void aFailedCallRecordsItsDurationAndErrorType() {
        var listener = new TokenCostListener("agent", meters, costs, genAi);
        var request = ChatRequest.builder().messages(UserMessage.from("bom dia")).modelName(GEMINI).build();
        var attributes = new java.util.concurrent.ConcurrentHashMap<Object, Object>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.GOOGLE_AI_GEMINI, attributes));
        listener.onError(new dev.langchain4j.model.chat.listener.ChatModelErrorContext(
                new IllegalStateException("429 RESOURCE_EXHAUSTED"),
                request, ModelProvider.GOOGLE_AI_GEMINI, attributes));

        // A provider that is rate-limiting is slow before it fails, and dropping the
        // failed calls out of the latency histogram is how an outage reads as healthy.
        var point = metric("gen_ai.client.operation.duration").getHistogramData().getPoints()
                .iterator().next();
        assertThat(point.getAttributes().asMap())
                .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("error.type"),
                        "IllegalStateException");
    }

    @Test
    @DisplayName("a response reporting no usage records no token points at all")
    void noUsageMeansNoTokenPoints() {
        var listener = new TokenCostListener("agent", meters, costs, genAi);
        var request = ChatRequest.builder().messages(UserMessage.from("bom dia")).modelName(GEMINI).build();
        var attributes = new java.util.concurrent.ConcurrentHashMap<Object, Object>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.GOOGLE_AI_GEMINI, attributes));
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("Bom dia!")).modelName(GEMINI).build(),
                request, ModelProvider.GOOGLE_AI_GEMINI, attributes));

        // A zero-token point is a claim that the call used no tokens. Not reporting usage
        // is a different claim, and the histogram must not turn one into the other.
        assertThat(recordedMetrics.collectAllMetrics().stream().map(MetricData::getName))
                .doesNotContain("gen_ai.client.token.usage");
    }
}
