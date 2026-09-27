package io.github.rodrigorjsf.agenticchat.evals.scenario;

import com.fasterxml.jackson.annotation.JsonValue;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

    public static final String HAPPY = "happy";
    public static final String UPSTREAM_FAILURE = "upstream-failure";
    public static final String MULTI_TURN = "multi-turn";

    /**
     * The named bad paths: the ones that count toward a domain's coverage floor. Out-of-scope,
     * cross-domain and multi-turn rows are kinds too, but not in this set.
     */
    public static final Set<String> BAD_PATH_KINDS = Collections.unmodifiableSortedSet(new TreeSet<>(Set.of(
            "missing-info", "out-of-territory", UPSTREAM_FAILURE, "injection", "ambiguous")));

    /** Every kind a row may declare. */
    public static final Set<String> KINDS = kinds();

    private static Set<String> kinds() {
        var kinds = new TreeSet<>(BAD_PATH_KINDS);
        kinds.addAll(Set.of(HAPPY, MULTI_TURN, "cross-domain", "out-of-scope"));
        return Collections.unmodifiableSortedSet(kinds);
    }

    /** Where a row's input came from. */
    public static final Set<String> SOURCES = Collections.unmodifiableSortedSet(new TreeSet<>(Set.of("trace", "synthetic", "user")));

    /**
     * @param trajectory how the agent must have reached its answer, across every turn; the outcome
     *                   is the last turn's
     * @param answer     what the last turn's answer must satisfy; absent when the row asserts nothing on it
     * @param turns      checks aimed at one turn of a multi-turn row; empty when the row has none
     * @param rubric     criteria the Grader scores, each pass or fail with a critique; reported only,
     *                   never a check, because the Grader is uncalibrated. Empty when the row has none
     */
    public record Expectation(TrajectoryExpectation trajectory, AnswerExpectation answer,
                              List<TurnExpectation> turns, List<String> rubric) {

        public Expectation {
            turns = turns == null ? List.of() : List.copyOf(turns);
            rubric = rubric == null ? List.of() : List.copyOf(rubric);
        }

        public Expectation(TrajectoryExpectation trajectory, AnswerExpectation answer) {
            this(trajectory, answer, List.of());
        }

        public Expectation(TrajectoryExpectation trajectory, AnswerExpectation answer,
                           List<TurnExpectation> turns) {
            this(trajectory, answer, turns, List.of());
        }
    }

    /**
     * The checks of one turn, run against that turn alone: its outcome, its tool calls, its answer.
     * Grounding still reaches back to tool results of earlier turns, because a value the agent
     * fetched in turn one and repeats from memory in turn two is grounded all the same.
     *
     * @param turn       1-based position in {@link Scenario#turns()}
     * @param trajectory what this turn must show; null checks nothing on it
     * @param answer     what this turn's answer must satisfy; null checks nothing on it
     */
    public record TurnExpectation(int turn, TrajectoryExpectation trajectory, AnswerExpectation answer) {
    }

    /**
     * @param outcome     the {@code outcome} the last turn's response must carry
     * @param toolsCalled tools that must each have executed at least once during the scenario
     */
    public record TrajectoryExpectation(String outcome, List<String> toolsCalled) {
    }

    /**
     * The deterministic answer checks a row asks for. A link outside the tool catalogue is
     * checked on every answer, whether or not the row names this.
     *
     * @param language the tag the answer must read as ({@code pt-BR} or {@code en}); null skips the check
     * @param contains text the answer must contain, each compared case-insensitively
     * @param grounded regular expressions that each find a value in the answer, capture group 1
     *                 when there is one, the whole match otherwise; every value found must appear in
     *                 a tool result captured during the same run
     */
    public record AnswerExpectation(String language, List<String> contains, List<String> grounded) {
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
         * <p>The key must already be in the catalogue. Catalogue entries bind per key, so a
         * mistyped one would create a new, unused entry: the real upstream would stay real while
         * the report still said it was faked.
         *
         * @param stubServer root URL of a server running the test {@code StubApiController}
         * @param catalogue  the catalogue keys of the application without the override
         * @throws IllegalArgumentException when {@link #catalogueKey} is not one of them
         */
        public Map<String, Object> properties(URI stubServer, Set<String> catalogue) {
            if (!catalogue.contains(catalogueKey)) {
                throw new IllegalArgumentException("Upstream override names '" + catalogueKey
                        + "', which is not a catalogue key; known keys: " + new TreeSet<>(catalogue));
            }
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
        SERVER_ERROR("server-error", null),
        /**
         * The stub answers after three seconds; the faked key's timeout drops to one so the
         * turn does not wait out the real six-second budget and its retry.
         */
        TIMEOUT("timeout", Duration.ofSeconds(1)),
        OVERSIZED("oversized", null);

        private final String route;
        private final Duration timeout;
        FailureShape(String route, Duration timeout) {
            this.route = route;
            this.timeout = timeout;
        }

        /** The name a row uses for the shape, which is also the stub route that produces it. */
        @JsonValue
        String route() {
            return route;
        }

        Duration timeout() {
            return timeout;
        }

        String description() {
            return switch (this) {
                case SERVER_ERROR -> "HTTP 500";
                case TIMEOUT -> "no answer within the " + timeout.toSeconds() + " s timeout";
                case OVERSIZED -> "a body larger than any response ceiling in the catalogue";
            };
        }
    }
}
