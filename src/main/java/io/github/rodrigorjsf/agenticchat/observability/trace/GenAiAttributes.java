package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.opentelemetry.api.common.AttributeKey;

/**
 * The OpenTelemetry GenAI semantic-convention attributes this project sets.
 *
 * <p>Separate from {@link LangfuseAttributes} because they answer to a different
 * authority and a different consumer. Langfuse gives its own namespace precedence, so
 * these buy nothing there — they are what a Tempo query, a Grafana panel or the
 * collector's spanmetrics connector can group by, none of which has ever heard of
 * {@code langfuse.*}.
 *
 * <p>{@code gen_ai.usage.*} used to be excluded here, on the reasoning that Langfuse
 * normalises that family by subtracting cache reads from input and would therefore count a
 * cache hit twice when both were sent. <b>Measured on 4.16.0, that is not what happens.</b>
 * A generation carrying both families reads back with the Langfuse buckets intact —
 * {@code input 100, input_cached_tokens 900, output 50, output_reasoning_tokens 200} —
 * byte for byte the same as one carrying only {@code langfuse.observation.usage_details}.
 * The {@code langfuse.*} namespace takes precedence, which is the same rule that governs
 * every other attribute here.
 *
 * <p><b>Precedence is not the same thing as discarding, and the difference decides where an
 * attribute belongs.</b> The OpenTelemetry pair ARRIVES at Langfuse — measured, it is filed
 * in the unmapped catch-all as {@code metadata["attributes.gen_ai.usage.input_tokens"]},
 * where nothing aggregates it and no chart reads it. So there are three consumers of these
 * two keys and each uses them differently: <b>Langfuse</b> keeps them as inert metadata and
 * charts the {@code langfuse.*} buckets; <b>Tempo</b> keeps them as ordinary span attributes
 * a TraceQL query can filter on, because the collector strips only
 * {@code gen_ai.(prompt|completion)}; and <b>Langfuse 3.80.0</b> reads them as the ONLY
 * source of usage it understands. Stripping them from the Langfuse leg would buy two fewer
 * metadata keys and cost the third consumer everything.
 *
 * <p>So they are set, and the reason to set them is not symmetry. Langfuse 3.80.0 ignores
 * {@code langfuse.observation.usage_details} entirely and reads usage ONLY from this
 * family: without these two keys every token count and every cost on that version reads
 * zero, on a trace that otherwise looks perfect. A Tempo query and a Grafana panel have
 * never heard of {@code langfuse.*} either.
 *
 * <p>The two conventions FOLD DIFFERENTLY and that is the whole care this needs.
 * {@code gen_ai.usage.input_tokens} is every prompt token, cache reads included; Langfuse's
 * buckets are mutually exclusive and split the same tokens across {@code input} and
 * {@code input_cached_tokens}. Both are written from one {@link
 * io.github.rodrigorjsf.agenticchat.observability.TokenUsageDetails}, which is the only
 * reason they cannot disagree — see {@code OtelAgentTracer.SpanObservation.usage}.
 */
public final class GenAiAttributes {

    private GenAiAttributes() {
    }

    public static final AttributeKey<String> SYSTEM = AttributeKey.stringKey("gen_ai.system");
    public static final AttributeKey<String> OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    public static final AttributeKey<String> REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    public static final AttributeKey<String> RESPONSE_MODEL = AttributeKey.stringKey("gen_ai.response.model");
    public static final AttributeKey<String> RESPONSE_FINISH_REASON =
            AttributeKey.stringKey("gen_ai.response.finish_reasons");
    public static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");

    /** Every prompt token, cache reads included — NOT Langfuse's exclusive {@code input} bucket. */
    public static final AttributeKey<Long> USAGE_INPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.input_tokens");

    /** Every completion token, reasoning tokens included. */
    public static final AttributeKey<Long> USAGE_OUTPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.output_tokens");
    public static final AttributeKey<String> TOOL_CALL_ID = AttributeKey.stringKey("gen_ai.tool.call.id");

    /** The operation names this application emits. */
    public static final String OPERATION_CHAT = "chat";
    public static final String OPERATION_EMBEDDINGS = "embeddings";
    public static final String OPERATION_EXECUTE_TOOL = "execute_tool";
    public static final String OPERATION_INVOKE_AGENT = "invoke_agent";
}
