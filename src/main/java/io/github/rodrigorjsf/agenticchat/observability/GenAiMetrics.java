package io.github.rodrigorjsf.agenticchat.observability;

import io.github.rodrigorjsf.agenticchat.observability.trace.GenAiAttributes;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.List;

/**
 * The OpenTelemetry GenAI client metrics, emitted beside the Micrometer counters.
 *
 * <h2>Why a second metrics pipeline is not a second opinion</h2>
 *
 * <p>This project already reports tokens, cost and latency through Micrometer, and chapter 7
 * argued for a while that a second pipeline would only be a way for two numbers to disagree.
 * That argument is right about the risk and wrong about the remedy. The remedy is that both
 * are emitted from ONE computation — {@link TokenCostListener} builds
 * {@link TokenUsageDetails} once and hands the same object to Micrometer, to this class and,
 * through the generation span, to Langfuse. What is left is not two opinions but one number
 * in three vocabularies, and the third vocabulary is the one every other GenAI tool speaks.
 *
 * <p>Micrometer's counters are this application's own: they are keyed by {@code role}, which
 * is a concept only this codebase has, and they are what the cost dashboard is built on.
 * These are the portable ones: a Grafana panel, an OpenTelemetry-native backend or another
 * team's tooling can read {@code gen_ai.client.token.usage} without knowing anything about
 * this repository.
 *
 * <h2>The two folds this class has to make, and why each is a decision</h2>
 *
 * <p><b>{@code gen_ai.token.type} allows exactly {@code input} and {@code output}.</b>
 * Langfuse's buckets are four and mutually exclusive. A cache read is still an input token
 * and a reasoning token is still an output token, so the fold is
 * {@code input = INPUT + INPUT_CACHED} and {@code output = OUTPUT + OUTPUT_REASONING}.
 * Dropping either half would make this histogram disagree with {@code usage_details} on the
 * same call, which is precisely the failure this class exists to avoid.
 *
 * <p><b>{@code gen_ai.provider.name} carries the LangChain4j provider spelling</b> —
 * {@code google_ai_gemini}, {@code open_ai} — and not the value from the semantic
 * conventions' registry. That is a deliberate trade: the registry value would be more
 * portable in the abstract, and it would not join to anything. The spans this application
 * exports carry {@code gen_ai.system = google_ai_gemini}, the collector's span_metrics
 * connector derives {@code gen_ai_request_model} from the same spans, and a metric that
 * named the provider differently could not be correlated with either. One vocabulary that
 * joins beats two that are each half right.
 *
 * <p>Metric names, units, attributes and the explicit bucket boundaries read 2026-08-23 from
 * {@code https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-metrics.md}.
 * The boundaries are stated rather than left to the SDK because the SDK's defaults top out
 * at 10 000: every token count above that would land in one overflow bucket and every
 * quantile over it would be a guess.
 */
@Singleton
public class GenAiMetrics {

    /** {@code gen_ai.token.type}, whose value set is closed at these two. */
    private static final AttributeKey<String> TOKEN_TYPE = AttributeKey.stringKey("gen_ai.token.type");
    private static final AttributeKey<String> PROVIDER_NAME = AttributeKey.stringKey("gen_ai.provider.name");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    private static final String INPUT = "input";
    private static final String OUTPUT = "output";

    private static final List<Long> TOKEN_BUCKETS = List.of(
            1L, 4L, 16L, 64L, 256L, 1024L, 4096L, 16384L, 65536L,
            262144L, 1048576L, 4194304L, 16777216L, 67108864L);

    private static final List<Double> DURATION_BUCKETS = List.of(
            0.01d, 0.02d, 0.04d, 0.08d, 0.16d, 0.32d, 0.64d, 1.28d,
            2.56d, 5.12d, 10.24d, 20.48d, 40.96d, 81.92d);

    private final LongHistogram tokenUsage;
    private final DoubleHistogram operationDuration;

    public GenAiMetrics(OpenTelemetry openTelemetry) {
        var meter = openTelemetry.getMeter("io.github.rodrigorjsf.agenticchat");
        this.tokenUsage = meter.histogramBuilder("gen_ai.client.token.usage")
                .setDescription("Number of input and output tokens used")
                .setUnit("{token}")
                .ofLongs()
                .setExplicitBucketBoundariesAdvice(TOKEN_BUCKETS)
                .build();
        this.operationDuration = meter.histogramBuilder("gen_ai.client.operation.duration")
                .setDescription("GenAI operation duration")
                .setUnit("s")
                .setExplicitBucketBoundariesAdvice(DURATION_BUCKETS)
                .build();
    }

    /**
     * Records one successful model call.
     *
     * <p>An empty {@code usage} records NO token point. A zero-token point would be a claim
     * that the call used no tokens, and a provider that did not report usage is making a
     * different claim — the same distinction {@code Observation.usage} draws by writing
     * nothing rather than writing zeros.
     */
    public void recordCall(String provider,
                           String requestModel,
                           String responseModel,
                           TokenUsageDetails usage,
                           Duration elapsed) {
        var base = attributes(provider, requestModel, responseModel);
        if (usage != null && !usage.isEmpty()) {
            long input = usage.promptTokens();
            long output = usage.completionTokens();
            if (input > 0) {
                tokenUsage.record(input, base.toBuilder().put(TOKEN_TYPE, INPUT).build());
            }
            if (output > 0) {
                tokenUsage.record(output, base.toBuilder().put(TOKEN_TYPE, OUTPUT).build());
            }
        }
        recordDuration(base, elapsed);
    }

    /**
     * Records one call that failed, with {@code error.type}.
     *
     * <p>The duration is recorded rather than skipped. A provider that is rate-limiting is
     * slow before it refuses, and a latency histogram that contains only the calls that
     * succeeded reads healthy through an outage.
     */
    public void recordFailure(String provider, String requestModel, Throwable error, Duration elapsed) {
        var attributes = attributes(provider, requestModel, null).toBuilder()
                .put(ERROR_TYPE, error == null ? "unknown" : error.getClass().getSimpleName())
                .build();
        recordDuration(attributes, elapsed);
    }

    private void recordDuration(Attributes attributes, Duration elapsed) {
        if (elapsed == null) {
            return;
        }
        // Seconds, per the convention — and this is the one line where the rest of this
        // stack disagrees on purpose: the span_metrics connector is pinned to milliseconds
        // because its unit is part of the Prometheus metric NAME. Two units, two names,
        // no ambiguity; one unit written into the wrong metric would be off by a thousand
        // and still plot.
        operationDuration.record(elapsed.toNanos() / 1_000_000_000d, attributes);
    }

    private static Attributes attributes(String provider, String requestModel, String responseModel) {
        AttributesBuilder builder = Attributes.builder()
                .put(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OPERATION_CHAT);
        put(builder, PROVIDER_NAME, provider);
        put(builder, GenAiAttributes.REQUEST_MODEL, requestModel);
        put(builder, GenAiAttributes.RESPONSE_MODEL, responseModel);
        return builder.build();
    }

    private static void put(AttributesBuilder builder, AttributeKey<String> key, String value) {
        if (value != null && !value.isBlank()) {
            builder.put(key, value);
        }
    }
}
