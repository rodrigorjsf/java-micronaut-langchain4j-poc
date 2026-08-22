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

    /**
     * Marks this observation as the head of its trace, for the v4 events write path.
     *
     * <p>Langfuse decides what a root is with a query-time predicate:
     * {@code parent_span_id = '' OR is_app_root = true}. Our root usually satisfies the
     * first half — the HTTP server span is excluded for {@code /api/chat}, so the turn has
     * no OTLP parent. Setting this as well means the trace still has a head if that
     * exclusion is ever removed, or if this service is called by another traced one.
     */
    public static final AttributeKey<Boolean> INTERNAL_IS_APP_ROOT =
            AttributeKey.booleanKey("langfuse.internal.is_app_root");

    /**
     * The same statement for the dual/legacy write path, which compares
     * {@code String(attribute) === "true"} instead. Both are set because which path runs
     * is a property of the Langfuse deployment, not of this application.
     */
    public static final AttributeKey<String> INTERNAL_AS_ROOT =
            AttributeKey.stringKey("langfuse.internal.as_root");

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
