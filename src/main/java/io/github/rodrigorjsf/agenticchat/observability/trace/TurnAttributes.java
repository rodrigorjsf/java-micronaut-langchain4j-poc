package io.github.rodrigorjsf.agenticchat.observability.trace;

import java.util.List;
import java.util.Map;

/**
 * What is true of a whole turn rather than of one step in it.
 *
 * @param traceName the name the trace is filed under
 * @param userId    the end user, when the deployment has one
 * @param sessionId the conversation — Langfuse groups traces by it
 * @param tags      free labels for filtering
 * @param metadata  top-level, filterable keys; anything else lands in a catch-all
 */
public record TurnAttributes(String traceName,
                             String userId,
                             String sessionId,
                             List<String> tags,
                             Map<String, String> metadata) {

    public TurnAttributes {
        tags = tags == null ? List.of() : List.copyOf(tags);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String traceName;
        private String userId;
        private String sessionId;
        private List<String> tags = List.of();
        private Map<String, String> metadata = Map.of();

        public Builder traceName(String traceName) {
            this.traceName = traceName;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public TurnAttributes build() {
            return new TurnAttributes(traceName, userId, sessionId, tags, metadata);
        }
    }
}
