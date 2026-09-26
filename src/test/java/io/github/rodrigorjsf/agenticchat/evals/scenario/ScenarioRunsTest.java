package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.CheckResult;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.Cost;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.Trajectory;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioRuns.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRunsTest {

    private static Scenario scenario(boolean critical) {
        return new Scenario("weather-happy-forecast-tomorrow", List.of("weather"), "happy", "synthetic",
                List.of("Vai chover amanhã em São Paulo?"),
                new Scenario.Expectation(new Scenario.TrajectoryExpectation("ANSWERED", List.of("get_weather"))),
                List.of("get_weather"), critical);
    }

    private static ScenarioResult pass(Scenario scenario) {
        return result(scenario, true);
    }

    private static ScenarioResult fail(Scenario scenario) {
        return result(scenario, false);
    }

    private static ScenarioResult result(Scenario scenario, boolean passed) {
        return new ScenarioResult(scenario, new Trajectory("ANSWERED", List.of(), List.of()), "ok",
                List.of(new CheckResult("outcome", passed, "reason")), Duration.ofMillis(10), 100, 20,
                Cost.NONE);
    }

    private static ScenarioResult skipped(Scenario scenario) {
        return ScenarioResult.skipped(scenario, "rate limited: RESOURCE_EXHAUSTED", Duration.ofMillis(10));
    }

    @Test
    @DisplayName("a critical scenario that passes 2 of 3 repetitions fails the gate")
    void aCriticalScenarioWithTwoOfThreeFailsTheGate() {
        var critical = scenario(true);
        var runs = new ScenarioRuns(critical, List.of(pass(critical), fail(critical), pass(critical)));

        assertThat(runs.passes()).isEqualTo(2);
        assertThat(runs.executed()).isEqualTo(3);
        assertThat(runs.failsGate()).isTrue();
    }

    @Test
    @DisplayName("a non-critical scenario that passes 2 of 3 is flaky and does not fail the gate")
    void aNonCriticalScenarioWithTwoOfThreeIsFlaky() {
        var reported = scenario(false);
        var runs = new ScenarioRuns(reported, List.of(pass(reported), fail(reported), pass(reported)));

        assertThat(runs.status()).isEqualTo(Status.FLAKY);
        assertThat(runs.failsGate()).isFalse();
    }

    @Test
    @DisplayName("a non-critical scenario that fails every repetition is reported failed, never gating")
    void aNonCriticalScenarioThatAlwaysFailsDoesNotGate() {
        var reported = scenario(false);
        var runs = new ScenarioRuns(reported, List.of(fail(reported), fail(reported), fail(reported)));

        assertThat(runs.status()).isEqualTo(Status.FAILED);
        assertThat(runs.failsGate()).isFalse();
    }

    @Test
    @DisplayName("a critical scenario that passes every repetition passes")
    void aCriticalScenarioThatAlwaysPassesPasses() {
        var critical = scenario(true);
        var runs = new ScenarioRuns(critical, List.of(pass(critical), pass(critical), pass(critical)));

        assertThat(runs.status()).isEqualTo(Status.PASSED);
        assertThat(runs.failsGate()).isFalse();
    }

    @Test
    @DisplayName("a rate-limited repetition is SKIPPED: it counts neither as a pass nor as a failure")
    void aSkippedRepetitionIsNotAFailure() {
        var critical = scenario(true);
        var runs = new ScenarioRuns(critical, List.of(pass(critical), skipped(critical), pass(critical)));

        assertThat(runs.skipped()).isEqualTo(1);
        assertThat(runs.executed()).isEqualTo(2);
        assertThat(runs.status()).isEqualTo(Status.PASSED);
        assertThat(runs.failsGate()).isFalse();
    }

    @Test
    @DisplayName("a scenario whose every repetition was rate limited is SKIPPED, not failed")
    void everyRepetitionSkippedIsSkipped() {
        var critical = scenario(true);
        var runs = new ScenarioRuns(critical, List.of(skipped(critical), skipped(critical), skipped(critical)));

        assertThat(runs.status()).isEqualTo(Status.SKIPPED);
        assertThat(runs.failsGate()).isFalse();
    }

    @Test
    @DisplayName("the number of repetitions defaults to 3 and is configurable")
    void repetitionsDefaultToThreeAndAreConfigurable() {
        assertThat(ScenarioRuns.parseRepetitions(null)).isEqualTo(3);
        assertThat(ScenarioRuns.parseRepetitions(" ")).isEqualTo(3);
        assertThat(ScenarioRuns.parseRepetitions("5")).isEqualTo(5);
        assertThatThrownBy(() -> ScenarioRuns.parseRepetitions("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scenarios.repetitions");
        assertThatThrownBy(() -> ScenarioRuns.parseRepetitions("three"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scenarios.repetitions");
    }
}
