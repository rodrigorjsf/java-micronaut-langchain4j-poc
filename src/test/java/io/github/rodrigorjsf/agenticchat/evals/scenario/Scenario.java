package io.github.rodrigorjsf.agenticchat.evals.scenario;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of the scenario dataset: a scripted conversation and what must be true after it.
 *
 * @param id        stable identifier, unique across the dataset
 * @param domains   the scenario domains the turn belongs to (more than one when it crosses them)
 * @param kind      happy path or one of the named bad-path kinds
 * @param source    where the input came from: trace, synthetic or user
 * @param turns     the user messages, sent in order on one conversation
 * @param expect    what the run must show afterwards
 * @param dependsOn the artifacts (skill, tool, catalogue key, prompt section) the row exercises
 * @param critical  whether a failure of this row is a failure of the suite
 * @param upstream  the one upstream this row fakes, or {@code null} when every upstream is real
 */
public record Scenario(String id,
                       List<String> domains,
                       String kind,
                       String source,
                       List<String> turns,
                       Expectation expect,
                       List<String> dependsOn,
                       boolean critical,
                       UpstreamFailure upstream) {

    public record Expectation(TrajectoryExpectation trajectory) {
    }

    /**
     * @param outcome     the {@code outcome} the last turn's response must carry
     * @param toolsCalled tools that must each have executed at least once during the scenario
     */
    public record TrajectoryExpectation(String outcome, List<String> toolsCalled) {
    }

    /**
     * A failure injected into one upstream while the model and every other upstream stay real.
     *
     * <p>Declared by the row rather than by a profile: the override exists only in the context
     * started for this scenario, so the rows around it still reach the real API behind the same
     * catalogue key.
     *
     * @param catalogueKey the {@code agentic.tools.apis} key pointed at the stub
     * @param failure      what the stub does to every request that reaches it
     */
    public record UpstreamFailure(String catalogueKey, FailureShape failure) {

        /**
         * The configuration that points {@link #catalogueKey} at the stub's failure route.
         *
         * @param stubServer root URL of a server running the test {@code StubApiController}
         */
        public Map<String, Object> properties(URI stubServer) {
            var prefix = "agentic.tools.apis." + catalogueKey + ".";
            var properties = new LinkedHashMap<String, Object>();
            properties.put(prefix + "base-url", stubServer.resolve("/stub/fail/" + failure.route()).toString());
            if (failure.timeout() != null) {
                properties.put(prefix + "timeout", failure.timeout().toString());
            }
            return properties;
        }

        /** How the report names the fake: which upstream, and what it did. */
        public String describe() {
            return catalogueKey + " faked: " + failure.description();
        }
    }

    /** The three ways an upstream fails that the tool door must turn into a value. */
    public enum FailureShape {
        @JsonProperty("server-error")
        SERVER_ERROR("server-error", null, "HTTP 500"),
        /**
         * The stub answers after three seconds; the faked key's timeout drops to one so the
         * turn does not wait out the real six-second budget and its retry.
         */
        @JsonProperty("timeout")
        TIMEOUT("timeout", Duration.ofSeconds(1), "no answer within the 1 s timeout"),
        @JsonProperty("oversized")
        OVERSIZED("oversized", null, "a body larger than any response ceiling in the catalogue");

        private final String route;
        private final Duration timeout;
        private final String description;

        FailureShape(String route, Duration timeout, String description) {
            this.route = route;
            this.timeout = timeout;
            this.description = description;
        }

        String route() {
            return route;
        }

        Duration timeout() {
            return timeout;
        }

        String description() {
            return description;
        }
    }
}
