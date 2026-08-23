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
 * <p>Deliberately DESCRIPTIVE attributes only. {@code gen_ai.usage.*} is not set here:
 * Langfuse normalises that family by subtracting cache reads from input, this project
 * has already done that subtraction in {@code TokenUsageDetails}, and sending both is
 * how a cache hit gets counted twice.
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
    public static final AttributeKey<String> TOOL_CALL_ID = AttributeKey.stringKey("gen_ai.tool.call.id");

    /** The operation names this application emits. */
    public static final String OPERATION_CHAT = "chat";
    public static final String OPERATION_EMBEDDINGS = "embeddings";
    public static final String OPERATION_EXECUTE_TOOL = "execute_tool";
    public static final String OPERATION_INVOKE_AGENT = "invoke_agent";
}
