package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.CheckResult;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.ToolCall;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.Trajectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportRendererTest {

    private static final Scenario WEATHER = new Scenario(
            "weather-happy-forecast-tomorrow",
            List.of("weather"),
            "happy",
            "synthetic",
            List.of("Vai chover amanhã em São Paulo?"),
            new Scenario.Expectation(new Scenario.TrajectoryExpectation("ANSWERED", List.of("get_weather")), null),
            List.of("get_weather"),
            true);

    private static ScenarioResult result(String answer, List<CheckResult> checks) {
        return new ScenarioResult(
                WEATHER,
                new Trajectory("ANSWERED", List.of("geo-and-weather"), List.of(
                        new ToolCall("activate_skill", "{\"skill_name\":\"geo-and-weather\"}", "activated"),
                        new ToolCall("get_weather", "{\"latitude\":\"-23.55\"}", "{\"precipitation_sum\":[0.0,12.4]}"))),
                answer,
                checks,
                Duration.ofMillis(1234),
                5400,
                87);
    }

    @Test
    @DisplayName("every field of a scenario run appears in the report")
    void rendersEveryFieldOfARun() {
        var html = EvalReportRenderer.render(List.of(result("Vai chover 12,4 mm amanhã.", List.of(
                new CheckResult("outcome", true, "outcome ANSWERED"),
                new CheckResult("tool called: get_weather", false, "expected get_weather to be called; called []")))));

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
    @DisplayName("the summary counts passed and failed scenarios")
    void summarisesPassesAndFailures() {
        var passed = result("ok", List.of(new CheckResult("outcome", true, "outcome ANSWERED")));
        var failed = result("ok", List.of(new CheckResult("outcome", false, "expected ANSWERED, got REFUSED")));

        var html = EvalReportRenderer.render(List.of(passed, failed));

        assertThat(html).contains("1 of 2 scenarios passed");
    }

    @Test
    @DisplayName("model text is escaped, so an answer can never become markup in the report")
    void escapesModelText() {
        var html = EvalReportRenderer.render(List.of(result(
                "<script>alert('x')</script> & <img src=x onerror=alert(1)>",
                List.of(new CheckResult("outcome", true, "outcome ANSWERED")))));

        assertThat(html)
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &lt;img src=x onerror=alert(1)&gt;")
                .doesNotContain("<script")
                .doesNotContain("<img");
    }

    @Test
    @DisplayName("the report is self-contained: it makes no request when opened")
    void makesNoExternalRequest() {
        var html = EvalReportRenderer.render(List.of(result("ok",
                List.of(new CheckResult("outcome", true, "outcome ANSWERED")))));

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

        var html = EvalReportRenderer.render(List.of(new ScenarioResult(scenario, run.trajectory(), run.answer(),
                run.checks(), run.latency(), run.inputTokens(), run.outputTokens())));

        assertThat(html)
                .contains("Expected answer")
                .contains("language pt-BR")
                .contains("contains [São Paulo]")
                .contains("grounded [(\\d+)\\s*mm&lt;]")
                .contains("<span class=\"pass\">PASS</span> no link outside the catalogue: the answer carries no link")
                .contains("<span class=\"fail\">FAIL</span> grounded: (\\d+)\\s*mm&lt;: [31,7] appear in no captured tool result");
    }
}
