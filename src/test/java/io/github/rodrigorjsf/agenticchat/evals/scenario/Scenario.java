package io.github.rodrigorjsf.agenticchat.evals.scenario;

import java.util.List;

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
 */
public record Scenario(String id,
                       List<String> domains,
                       String kind,
                       String source,
                       List<String> turns,
                       Expectation expect,
                       List<String> dependsOn,
                       boolean critical) {

    /**
     * @param trajectory how the agent must have reached its answer
     * @param answer     what the answer itself must satisfy; absent when the row asserts nothing on it
     */
    public record Expectation(TrajectoryExpectation trajectory, AnswerExpectation answer) {
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
}
