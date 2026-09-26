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
            new Scenario.Expectation(new Scenario.TrajectoryExpectation("ANSWERED", List.of("get_weather"))),
            List.of("get_weather"),
            true,
            null);

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
    @DisplayName("an upstream-failure run names the upstream that was faked and how; a real run says none was")
    void namesTheFakedUpstream() {
        var faked = new Scenario("weather-upstream-timeout", List.of("weather"), "upstream-failure", "synthetic",
                List.of("Qual a previsão para amanhã em Recife?"),
                new Scenario.Expectation(new Scenario.TrajectoryExpectation("ANSWERED", List.of("get_weather"))),
                List.of("open-meteo-forecast"), false,
                new Scenario.UpstreamFailure("open-meteo-forecast", Scenario.FailureShape.TIMEOUT));
        var fakedRun = new ScenarioResult(faked, new Trajectory("ANSWERED", List.of(), List.of()), "indisponível",
                List.of(new CheckResult("outcome", true, "outcome ANSWERED")), Duration.ofMillis(10), 1, 1);

        var html = EvalReportRenderer.render(List.of(fakedRun, result("ok",
                List.of(new CheckResult("outcome", true, "outcome ANSWERED")))));

        assertThat(html)
                .contains("<th>Faked upstream</th><td>open-meteo-forecast faked: no answer within the 1 s timeout</td>")
                .contains("<th>Faked upstream</th><td>none: every upstream was real</td>");
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
}
