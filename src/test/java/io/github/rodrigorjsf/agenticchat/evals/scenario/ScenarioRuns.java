package io.github.rodrigorjsf.agenticchat.evals.scenario;

import java.util.List;

/**
 * Every repetition of one scenario, and what they add up to.
 *
 * <p>A real model is not deterministic, so one run is a coin flip: a scenario runs N times and
 * reports a pass rate. Only a {@code critical} scenario gates, and it must pass every repetition
 * that ran. A rate-limited repetition is SKIPPED — it counts neither way.
 */
public record ScenarioRuns(Scenario scenario, List<ScenarioResult> repetitions) {

    /** The system property that overrides {@link #DEFAULT_REPETITIONS}. */
    public static final String REPETITIONS_PROPERTY = "scenarios.repetitions";

    public static final int DEFAULT_REPETITIONS = 3;

    public enum Verdict {
        /** Every repetition that ran passed. */
        PASSED,
        /** Some repetitions passed and some failed. */
        FLAKY,
        /** Every repetition that ran failed. */
        FAILED,
        /** No repetition ran: each one hit a rate limit. */
        SKIPPED
    }

    public ScenarioRuns {
        repetitions = List.copyOf(repetitions);
    }

    public long passes() {
        return repetitions.stream().filter(ScenarioResult::passed).count();
    }

    public long skipped() {
        return repetitions.stream().filter(ScenarioResult::skipped).count();
    }

    public long executed() {
        return repetitions.size() - skipped();
    }

    public Verdict verdict() {
        long executed = executed();
        long passes = passes();
        if (executed == 0) {
            return Verdict.SKIPPED;
        }
        if (passes == executed) {
            return Verdict.PASSED;
        }
        return passes == 0 ? Verdict.FAILED : Verdict.FLAKY;
    }

    /** A critical scenario fails the eval on any failed repetition; any other scenario never does. */
    public boolean failsGate() {
        return scenario.critical() && passes() < executed();
    }

    /**
     * @param raw the value of {@value #REPETITIONS_PROPERTY}, or {@code null} when unset
     */
    public static int repetitions(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_REPETITIONS;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            if (value >= 1) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the same message as a non-positive number
        }
        throw new IllegalArgumentException(REPETITIONS_PROPERTY + " must be a positive integer, got '" + raw + "'");
    }
}
