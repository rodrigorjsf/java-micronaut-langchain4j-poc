package io.github.rodrigorjsf.agenticchat.observability.trace;

import java.util.Map;

/**
 * Which experiment a trace belongs to, and which row of it.
 *
 * <p>There is no experiment entity on the wire. Langfuse synthesises an experiment out of
 * ordinary traces that happen to carry the same {@code langfuse.experiment.id}, one trace
 * per item, which is why this is an attribute carrier rather than a client for some
 * endpoint.
 *
 * <p>The record holds both levels the documentation separates — the experiment, shared by
 * every item, and the item, different on each — because they are propagated by the same
 * mechanism and splitting them would buy two context keys and one more thing to forget.
 * {@link #withItem} is the transition from the first level to the second.
 *
 * <p>Field names read 2026-08-23 from
 * {@code https://langfuse.com/integrations/native/opentelemetry/experiments}.
 *
 * @param id                the experiment, unique per run
 * @param name              the experiment's name, unique per run
 * @param datasetId         a Langfuse-managed {@code Dataset.id}, or a stable local
 *                          identifier when the rows do not come from Langfuse
 * @param description       free text shown beside the run, or null
 * @param metadata          top-level, filterable experiment keys
 * @param itemId            the row — a {@code DatasetItem.id} for a managed dataset
 * @param itemVersion       the dataset version, for managed datasets only; null for local
 *                          data, where a version would name something that does not exist
 * @param rootObservationId the span id of the item trace's root, which only exists once
 *                          that span has been started
 */
public record ExperimentAttributes(String id,
                                   String name,
                                   String datasetId,
                                   String description,
                                   Map<String, String> metadata,
                                   String itemId,
                                   String itemVersion,
                                   String rootObservationId) {

    public ExperimentAttributes {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * @return a copy that also names one item and the root observation of its trace
     */
    public ExperimentAttributes withItem(String itemId, String itemVersion, String rootObservationId) {
        return new ExperimentAttributes(id, name, datasetId, description, metadata,
                itemId, itemVersion, rootObservationId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String name;
        private String datasetId;
        private String description;
        private Map<String, String> metadata = Map.of();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder datasetId(String datasetId) {
            this.datasetId = datasetId;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ExperimentAttributes build() {
            return new ExperimentAttributes(id, name, datasetId, description, metadata,
                    null, null, null);
        }
    }
}
