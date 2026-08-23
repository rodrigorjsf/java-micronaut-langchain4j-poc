package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.annotation.Requires;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import jakarta.inject.Singleton;

/**
 * Copies the turn's attributes onto every span the turn starts.
 *
 * <p>Langfuse v4 queries observations, not traces. An attribute that lives only on the
 * root span is therefore unavailable when filtering or aggregating its children, and the
 * failure is silent: the filter returns one observation out of twelve and looks like it
 * worked. Doing it in a processor rather than at each call site is the difference between
 * one class and every seam remembering.
 */
@Singleton
@Requires(beans = DeploymentIdentity.class)
public class TurnAttributesSpanProcessor implements SpanProcessor {

    private final DeploymentIdentity deployment;

    public TurnAttributesSpanProcessor(DeploymentIdentity deployment) {
        this.deployment = deployment;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        set(span, LangfuseAttributes.ENVIRONMENT.getKey(), deployment.environment());
        set(span, LangfuseAttributes.VERSION.getKey(), deployment.version());
        set(span, LangfuseAttributes.RELEASE.getKey(), deployment.release());

        TurnContext.from(parentContext).ifPresent(turn -> {
            set(span, LangfuseAttributes.TRACE_NAME.getKey(), turn.traceName());
            set(span, LangfuseAttributes.USER_ID.getKey(), turn.userId());
            set(span, LangfuseAttributes.SESSION_ID.getKey(), turn.sessionId());
            if (!turn.tags().isEmpty()) {
                span.setAttribute(LangfuseAttributes.TRACE_TAGS, turn.tags());
            }
            turn.metadata().forEach((key, value) ->
                    set(span, LangfuseAttributes.traceMetadata(key).getKey(), value));
        });

        // The experiment level rides the same mechanism for the same reason: Langfuse v4
        // queries observations, so an experiment id on the item root alone leaves every
        // child of it unattributable — and the run then looks like one observation per
        // row rather than a truncated one.
        ExperimentContext.from(parentContext).ifPresent(experiment -> {
            set(span, LangfuseAttributes.EXPERIMENT_ID.getKey(), experiment.id());
            set(span, LangfuseAttributes.EXPERIMENT_NAME.getKey(), experiment.name());
            set(span, LangfuseAttributes.EXPERIMENT_DATASET_ID.getKey(), experiment.datasetId());
            set(span, LangfuseAttributes.EXPERIMENT_DESCRIPTION.getKey(), experiment.description());
            set(span, LangfuseAttributes.EXPERIMENT_ITEM_ID.getKey(), experiment.itemId());
            set(span, LangfuseAttributes.EXPERIMENT_ITEM_VERSION.getKey(), experiment.itemVersion());
            set(span, LangfuseAttributes.EXPERIMENT_ITEM_ROOT_OBSERVATION_ID.getKey(),
                    experiment.rootObservationId());
            experiment.metadata().forEach((key, value) ->
                    set(span, LangfuseAttributes.experimentMetadata(key).getKey(), value));
        });
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // Nothing: everything this processor knows is known at start time, and an
        // attribute written on end would arrive after a SimpleSpanProcessor exported it.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    private static void set(ReadWriteSpan span, String key, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(key, value);
        }
    }
}
