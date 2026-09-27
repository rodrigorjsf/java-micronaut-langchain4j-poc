package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.observability.TokenUsageDetails;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationType;
import io.github.rodrigorjsf.agenticchat.testsupport.RecordingAgentTracer.Recorded;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Everything one repetition of a scenario produced: what the agent did, what it said, and how
 * each check went.
 *
 * @param latency      wall-clock time across every turn of the scenario
 * @param inputTokens  input tokens reported by the endpoint, summed across turns
 * @param outputTokens output tokens reported by the endpoint, summed across turns
 * @param cost         what the model calls of this repetition cost, as the application priced them
 * @param skipped      the repetition hit a provider rate limit or quota, so it measured nothing:
 *                     it is neither a pass nor a failure
 * @param turns        each turn that completed, in order; {@code trajectory} and {@code answer} are
 *                     the whole scenario seen at once
 */
public record ScenarioResult(Scenario scenario,
                             Trajectory trajectory,
                             String answer,
                             List<CheckResult> checks,
                             Duration latency,
                             long inputTokens,
                             long outputTokens,
                             Cost cost,
                             boolean skipped,
                             List<TurnResult> turns) {

    public ScenarioResult {
        checks = List.copyOf(checks);
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public ScenarioResult(Scenario scenario, Trajectory trajectory, String answer, List<CheckResult> checks,
                          Duration latency, long inputTokens, long outputTokens, Cost cost) {
        this(scenario, trajectory, answer, checks, latency, inputTokens, outputTokens, cost, false, List.of());
    }

    /**
     * A repetition the provider refused on quota. Scoring it as a failure would report a rate limit
     * as a regression.
     */
    public static ScenarioResult skipped(Scenario scenario, String reason, Duration latency) {
        return skipped(scenario, reason, latency, Cost.NONE, List.of());
    }

    /**
     * @param cost      what the calls made before the quota error cost — they are billed all the same
     * @param completed the turns that finished before the quota error, kept for the report
     */
    public static ScenarioResult skipped(Scenario scenario, String reason, Duration latency, Cost cost,
                                         List<TurnResult> completed) {
        return new ScenarioResult(scenario, new Trajectory(null, List.of(), List.of()), null,
                List.of(new CheckResult("rate limit", false, reason)), latency, 0, 0, cost, true, completed);
    }

    public boolean passed() {
        return !skipped && !checks.isEmpty() && checks.stream().allMatch(CheckResult::passed);
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

    /**
     * One turn of the conversation: what the user sent and what that turn alone produced.
     *
     * @param number         1-based position in the scenario's turns
     * @param conversationId the id the endpoint answered with; the same on every turn of a run
     * @param trajectory     the outcome, activations and tool calls of this turn only
     */
    public record TurnResult(int number, String message, String conversationId, Trajectory trajectory,
                             String answer) {
    }

    /** One tool execution as the tool listener recorded it: the model's arguments and the tool's result. */
    public record ToolCall(String name, String arguments, String result) {
    }

    /** One check, with the reason a reader needs when it fails. */
    public record CheckResult(String name, boolean passed, String reason) {
    }

    /**
     * Estimated spend, summed from the cost the application's own {@code CostCalculator} put on
     * each model call — no second price table.
     *
     * @param usd           the priced calls' total
     * @param unpricedCalls model calls with no configured price, which {@code usd} therefore leaves
     *                      out; counted so an absent price never reads as a confident zero
     */
    public record Cost(BigDecimal usd, int unpricedCalls) {

        public static final Cost NONE = new Cost(BigDecimal.ZERO, 0);

        /**
         * Reads the cost {@code LangfuseChatModelListener} attached to every model call. An empty
         * breakdown on a call that reported usage is {@code CostCalculator}'s answer for a model
         * with no configured price.
         */
        public static Cost of(List<Recorded> observations) {
            var usd = BigDecimal.ZERO;
            int unpriced = 0;
            for (var observation : observations) {
                if (observation.type() != ObservationType.GENERATION || observation.usage() == null
                        || observation.usage().isEmpty()) {
                    continue;
                }
                var total = observation.cost() == null ? null : observation.cost().get(TokenUsageDetails.TOTAL);
                if (total == null) {
                    unpriced++;
                } else {
                    usd = usd.add(total);
                }
            }
            return new Cost(usd, unpriced);
        }

        public Cost plus(Cost other) {
            return new Cost(usd.add(other.usd), unpricedCalls + other.unpricedCalls);
        }
    }
}
