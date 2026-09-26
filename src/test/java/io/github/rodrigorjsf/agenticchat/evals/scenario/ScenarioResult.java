package io.github.rodrigorjsf.agenticchat.evals.scenario;

import java.time.Duration;
import java.util.List;

/**
 * Everything one scenario run produced: what the agent did, what it said, and how each check went.
 *
 * @param latency      wall-clock time across every turn of the scenario
 * @param inputTokens  input tokens reported by the endpoint, summed across turns
 * @param outputTokens output tokens reported by the endpoint, summed across turns
 */
public record ScenarioResult(Scenario scenario,
                             Trajectory trajectory,
                             String answer,
                             List<CheckResult> checks,
                             Duration latency,
                             long inputTokens,
                             long outputTokens) {

    public ScenarioResult {
        checks = List.copyOf(checks);
    }

    public boolean passed() {
        return !checks.isEmpty() && checks.stream().allMatch(CheckResult::passed);
    }

    /**
     * What the agent did during the scenario.
     *
     * @param outcome     the {@code outcome} of the last turn's response
     * @param activations skills activated, in order
     * @param toolCalls   every tool execution, in order, activations included
     */
    public record Trajectory(String outcome, List<String> activations, List<ToolCall> toolCalls) {

        public Trajectory {
            activations = List.copyOf(activations);
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /** One tool execution as the tool listener recorded it: the model's arguments and the tool's result. */
    public record ToolCall(String name, String arguments, String result) {
    }

    /** One check, with the reason a reader needs when it fails. */
    public record CheckResult(String name, boolean passed, String reason) {
    }
}
