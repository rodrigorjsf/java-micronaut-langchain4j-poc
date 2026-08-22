package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.opentelemetry.api.common.AttributeKey;

import java.util.List;

/**
 * Every span-attribute name Langfuse reads, in one place.
 *
 * <p>One place because these are a wire contract with a server this codebase does not
 * own. A misspelt key does not fail: the attribute is simply filed under
 * {@code metadata.attributes} as an unmapped extra, the field it was meant to populate
 * stays empty, and the trace looks plausible. Collecting them here makes the contract
 * greppable and a rename one edit.
 *
 * <p>Names read 2026-08-22 from
 * {@code https://langfuse.com/integrations/native/opentelemetry#property-mapping}.
 *
 * <p>The {@code langfuse.*} namespace takes precedence over the generic OpenTelemetry
 * GenAI conventions, so this project sets it explicitly rather than hoping a convention
 * is mapped the way it expects.
 */
public final class LangfuseAttributes {

    private LangfuseAttributes() {
    }

    // --- Trace level. Langfuse v4 queries observations, not traces, so every one of
    // --- these must be copied onto EVERY span in the trace, not only the root.
    public static final AttributeKey<String> TRACE_NAME = AttributeKey.stringKey("langfuse.trace.name");
    public static final AttributeKey<String> USER_ID = AttributeKey.stringKey("langfuse.user.id");
    public static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("langfuse.session.id");
    public static final AttributeKey<String> RELEASE = AttributeKey.stringKey("langfuse.release");
    public static final AttributeKey<String> VERSION = AttributeKey.stringKey("langfuse.version");
    public static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey("langfuse.environment");
    public static final AttributeKey<List<String>> TRACE_TAGS = AttributeKey.stringArrayKey("langfuse.trace.tags");

    // --- Observation level.
    public static final AttributeKey<String> OBSERVATION_TYPE =
            AttributeKey.stringKey("langfuse.observation.type");
    public static final AttributeKey<String> OBSERVATION_INPUT =
            AttributeKey.stringKey("langfuse.observation.input");
    public static final AttributeKey<String> OBSERVATION_OUTPUT =
            AttributeKey.stringKey("langfuse.observation.output");
    public static final AttributeKey<String> OBSERVATION_LEVEL =
            AttributeKey.stringKey("langfuse.observation.level");
    public static final AttributeKey<String> OBSERVATION_STATUS_MESSAGE =
            AttributeKey.stringKey("langfuse.observation.status_message");

    // --- Generation and embedding only.
    public static final AttributeKey<String> MODEL_NAME =
            AttributeKey.stringKey("langfuse.observation.model.name");
    public static final AttributeKey<String> MODEL_PARAMETERS =
            AttributeKey.stringKey("langfuse.observation.model.parameters");
    public static final AttributeKey<String> USAGE_DETAILS =
            AttributeKey.stringKey("langfuse.observation.usage_details");
    public static final AttributeKey<String> COST_DETAILS =
            AttributeKey.stringKey("langfuse.observation.cost_details");
    public static final AttributeKey<String> COMPLETION_START_TIME =
            AttributeKey.stringKey("langfuse.observation.completion_start_time");

    private static final String OBSERVATION_METADATA_PREFIX = "langfuse.observation.metadata.";
    private static final String TRACE_METADATA_PREFIX = "langfuse.trace.metadata.";

    /**
     * Langfuse only filters on TOP-LEVEL metadata keys. An ordinary OpenTelemetry
     * attribute lands under {@code metadata.attributes} and cannot be filtered on at
     * all, so anything worth querying has to carry this prefix.
     */
    public static AttributeKey<String> observationMetadata(String key) {
        return AttributeKey.stringKey(OBSERVATION_METADATA_PREFIX + key);
    }

    public static AttributeKey<String> traceMetadata(String key) {
        return AttributeKey.stringKey(TRACE_METADATA_PREFIX + key);
    }
}
