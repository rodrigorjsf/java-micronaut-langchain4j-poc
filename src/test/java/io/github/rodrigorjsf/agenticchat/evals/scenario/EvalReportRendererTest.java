package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.CheckResult;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.Cost;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.ToolCall;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.Trajectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportRendererTest {

    private static final Scenario WEATHER = scenario("weather-happy-forecast-tomorrow", true);

    private static Scenario scenario(String id, boolean critical) {
        return new Scenario(
                id,
                List.of("weather"),
                "happy",
                "synthetic",
                List.of("Vai chover amanhã em São Paulo?"),
                new Scenario.Expectation(new Scenario.TrajectoryExpectation("ANSWERED", List.of("get_weather")), null),
                List.of("get_weather"),
                critical);
    }

    private static ScenarioResult result(String answer, List<CheckResult> checks) {
        return result(WEATHER, answer, checks);
    }

    private static ScenarioResult result(Scenario scenario, String answer, List<CheckResult> checks) {
        return new ScenarioResult(
                scenario,
                new Trajectory("ANSWERED", List.of("geo-and-weather"), List.of(
                        new ToolCall("activate_skill", "{\"skill_name\":\"geo-and-weather\"}", "activated"),
                        new ToolCall("get_weather", "{\"latitude\":\"-23.55\"}", "{\"precipitation_sum\":[0.0,12.4]}"))),
                answer,
                checks,
                Duration.ofMillis(1234),
                5400,
                87,
                new Cost(new BigDecimal("0.0015"), 0));
    }

    private static ScenarioResult passing(Scenario scenario) {
        return result(scenario, "ok", List.of(new CheckResult("outcome", true, "outcome ANSWERED")));
    }

    private static ScenarioResult failing(Scenario scenario) {
        return result(scenario, "ok", List.of(new CheckResult("outcome", false, "expected ANSWERED, got REFUSED")));
    }

    private static ScenarioRuns once(ScenarioResult result) {
        return new ScenarioRuns(result.scenario(), List.of(result));
    }

    @Test
    @DisplayName("every field of a scenario run appears in the report")
    void rendersEveryFieldOfARun() {
        var html = EvalReportRenderer.render(List.of(once(result("Vai chover 12,4 mm amanhã.", List.of(
                new CheckResult("outcome", true, "outcome ANSWERED"),
                new CheckResult("tool called: get_weather", false, "expected get_weather to be called; called []"))))));

        assertThat(html)
                .contains("weather-happy-forecast-tomorrow")
                .contains("weather")
                .contains("happy")
                .contains("synthetic")
                .contains("critical")
                .contains("Vai chover amanhã em São Paulo?")
                .contains("ANSWERED")
                .contains("geo-and-weather")
                .contains("activate_skill")
                .contains("-23.55")
                .contains("12.4")
                .contains("Vai chover 12,4 mm amanhã.")
                .contains("tool called: get_weather")
                .contains("expected get_weather to be called; called []")
                .contains("1234 ms")
                .contains("5400")
                .contains("87");
    }

    @Test
    @DisplayName("each repetition is shown with its own verdict, and the scenario with its pass rate")
    void showsEveryRepetition() {
        var runs = new ScenarioRuns(WEATHER, List.of(passing(WEATHER), failing(WEATHER),
                ScenarioResult.skipped(WEATHER, "SKIPPED: the provider rate-limited the turn (RESOURCE_EXHAUSTED)",
                        Duration.ofMillis(40))));

        var html = EvalReportRenderer.render(List.of(runs));

        assertThat(html)
                .contains("Repetition 1 of 3")
                .contains("Repetition 2 of 3")
                .contains("Repetition 3 of 3")
                .contains("1 of 2 passed")
                .contains("1 skipped")
                .contains("expected ANSWERED, got REFUSED")
                .contains("SKIPPED: the provider rate-limited the turn (RESOURCE_EXHAUSTED)");
    }

    @Test
    @DisplayName("the summary counts passes, names the gate failures and the flaky scenarios")
    void summarisesPassesGateFailuresAndFlakiness() {
        var passed = scenario("always-passes", true);
        var criticalFlaky = scenario("critical-two-of-three", true);
        var reportedFlaky = scenario("reported-two-of-three", false);

        var html = EvalReportRenderer.render(List.of(
                new ScenarioRuns(passed, List.of(passing(passed), passing(passed), passing(passed))),
                new ScenarioRuns(criticalFlaky, List.of(passing(criticalFlaky), failing(criticalFlaky),
                        passing(criticalFlaky))),
                new ScenarioRuns(reportedFlaky, List.of(passing(reportedFlaky), failing(reportedFlaky),
                        passing(reportedFlaky)))));

        assertThat(html).contains("1 of 3 scenarios passed");
        assertThat(summaryLine(html, "Gate failures")).contains("critical-two-of-three")
                .doesNotContain("reported-two-of-three");
        assertThat(summaryLine(html, "Flaky")).contains("critical-two-of-three (2/3)")
                .contains("reported-two-of-three (2/3)")
                .doesNotContain("always-passes");
    }

    @Test
    @DisplayName("the summary totals tokens and estimated cost across every repetition")
    void totalsTokensAndCost() {
        var html = EvalReportRenderer.render(List.of(
                new ScenarioRuns(WEATHER, List.of(passing(WEATHER), passing(WEATHER))),
                new ScenarioRuns(WEATHER, List.of(new ScenarioResult(WEATHER,
                        new Trajectory("ANSWERED", List.of(), List.of()), "ok",
                        List.of(new CheckResult("outcome", true, "outcome ANSWERED")), Duration.ofMillis(5),
                        100, 10, new Cost(BigDecimal.ZERO, 2))))));

        assertThat(summaryLine(html, "Tokens")).contains("10900 in").contains("184 out");
        assertThat(summaryLine(html, "Estimated cost")).contains("$0.0030").contains("2 model calls unpriced");
    }

    @Test
    @DisplayName("model text is escaped, so an answer can never become markup in the report")
    void escapesModelText() {
        var html = EvalReportRenderer.render(List.of(once(result(
                "<script>alert('x')</script> & <img src=x onerror=alert(1)>",
                List.of(new CheckResult("outcome", true, "outcome ANSWERED"))))));

        assertThat(html)
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &lt;img src=x onerror=alert(1)&gt;")
                .doesNotContain("<script")
                .doesNotContain("<img");
    }

    @Test
    @DisplayName("the report is self-contained: it makes no request when opened")
    void makesNoExternalRequest() {
        var html = EvalReportRenderer.render(List.of(once(result("ok",
                List.of(new CheckResult("outcome", true, "outcome ANSWERED"))))));

        assertThat(html)
                .startsWith("<!doctype html>")
                .contains("<style>")
                .doesNotContain("<link")
                .doesNotContain("<script")
                .doesNotContain("src=")
                .doesNotContain("url(")
                .doesNotContain("@import");
    }

    @Test
    @DisplayName("the answer expectation and each deterministic answer check appear with their verdict and reason")
    void rendersTheAnswerLayer() {
        var scenario = new Scenario(WEATHER.id(), WEATHER.domains(), WEATHER.kind(), WEATHER.source(), WEATHER.turns(),
                new Scenario.Expectation(WEATHER.expect().trajectory(), new Scenario.AnswerExpectation(
                        "pt-BR", List.of("São Paulo"), List.of("(\\d+)\\s*mm<"))),
                WEATHER.dependsOn(), WEATHER.critical());
        var run = result("Vai chover 31,7 mm.", List.of(
                new CheckResult("no link outside the catalogue", true, "the answer carries no link"),
                new CheckResult("grounded: (\\d+)\\s*mm<", false, "[31,7] appear in no captured tool result")));

        var html = EvalReportRenderer.render(List.of(once(new ScenarioResult(scenario, run.trajectory(), run.answer(),
                run.checks(), run.latency(), run.inputTokens(), run.outputTokens(), run.cost()))));

        assertThat(html)
                .contains("Expected answer")
                .contains("language pt-BR")
                .contains("contains [São Paulo]")
                .contains("grounded [(\\d+)\\s*mm&lt;]")
                .contains("<span class=\"pass\">PASS</span> no link outside the catalogue: the answer carries no link")
                .contains("<span class=\"fail\">FAIL</span> grounded: (\\d+)\\s*mm&lt;: [31,7] appear in no captured tool result");
    }

    /** The one summary row that starts with {@code label}, so an assertion cannot match elsewhere. */
    private static String summaryLine(String html, String label) {
        return html.lines()
                .filter(line -> line.startsWith("<li><strong>" + label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no summary line " + label + " in:\n" + html));
    }
}
