package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.CheckResult;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.ToolCall;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.Trajectory;

import java.util.ArrayList;
import java.util.List;

/**
 * The first layer of checks: did the agent reach its answer the way the row says it must.
 */
final class TrajectoryCheck {

    private TrajectoryCheck() {
    }

    static List<CheckResult> evaluate(Scenario.TrajectoryExpectation expected, Trajectory observed) {
        var checks = new ArrayList<CheckResult>();
        if (expected.outcome() != null) {
            boolean same = expected.outcome().equals(observed.outcome());
            checks.add(new CheckResult("outcome", same, same
                    ? "outcome " + observed.outcome()
                    : "expected outcome " + expected.outcome() + ", got " + observed.outcome()));
        }
        var called = observed.toolCalls().stream().map(ToolCall::name).toList();
        for (String tool : expected.toolsCalled() == null ? List.<String>of() : expected.toolsCalled()) {
            boolean ran = called.contains(tool);
            checks.add(new CheckResult("tool called: " + tool, ran, ran
                    ? tool + " was called"
                    : "expected " + tool + " to be called; called " + called));
        }
        return checks;
    }
}
