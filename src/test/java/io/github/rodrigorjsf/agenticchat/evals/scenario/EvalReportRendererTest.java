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
import java.util.Set;

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
                critical,
                null);
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
        var html = render(List.of(once(result("Vai chover 12,4 mm amanhã.", List.of(
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

        var html = render(List.of(runs));

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

        var html = render(List.of(
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
        var html = render(List.of(
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
        var html = render(List.of(once(result(
                "<script>alert('x')</script> & <img src=x onerror=alert(1)>",
                List.of(new CheckResult("outcome", true, "outcome ANSWERED"))))));

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
                new Scenario.Expectation(new Scenario.TrajectoryExpectation("ANSWERED", List.of("get_weather")), null),
                List.of("open-meteo-forecast"), false,
                new Scenario.UpstreamFailure("open-meteo-forecast", Scenario.FailureShape.TIMEOUT));
        var fakedRun = new ScenarioResult(faked, new Trajectory("ANSWERED", List.of(), List.of()), "indisponível",
                List.of(new CheckResult("outcome", true, "outcome ANSWERED")), Duration.ofMillis(10), 1, 1, ScenarioResult.Cost.NONE);

        var html = render(List.of(once(fakedRun), once(result("ok",
                List.of(new CheckResult("outcome", true, "outcome ANSWERED"))))));

        assertThat(html)
                .contains("<th>Faked upstream</th><td>open-meteo-forecast faked: no answer within the 1 s timeout</td>")
                .contains("<th>Faked upstream</th><td>none: every upstream was real</td>");
    }

    @Test
    @DisplayName("the report is self-contained: it makes no request when opened")
    void makesNoExternalRequest() {
        var html = render(List.of(once(result("ok",
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
                WEATHER.dependsOn(), WEATHER.critical(), null);
        var run = result("Vai chover 31,7 mm.", List.of(
                new CheckResult("no link outside the catalogue", true, "the answer carries no link"),
                new CheckResult("grounded: (\\d+)\\s*mm<", false, "[31,7] appear in no captured tool result")));

        var html = render(List.of(once(new ScenarioResult(scenario, run.trajectory(), run.answer(),
                run.checks(), run.latency(), run.inputTokens(), run.outputTokens(), run.cost()))));

        assertThat(html)
                .contains("Expected answer")
                .contains("language pt-BR")
                .contains("contains [São Paulo]")
                .contains("grounded [(\\d+)\\s*mm&lt;]")
                .contains("<span class=\"pass\">PASS</span> no link outside the catalogue: the answer carries no link")
                .contains("<span class=\"fail\">FAIL</span> grounded: (\\d+)\\s*mm&lt;: [31,7] appear in no captured tool result");
    }

    @Test
    @DisplayName("coverage per domain counts a scenario with two domains toward both")
    void aCrossDomainScenarioCountsTowardBothDomains() {
        var cross = new Scenario("dollar-and-weather", List.of("currency", "weather"), "cross-domain", "synthetic",
                List.of("Qual a cotação do dólar e vai chover amanhã em São Paulo?"),
                WEATHER.expect(), List.of("get_weather"), false, null);

        var html = render(List.of(once(passing(WEATHER)), once(passing(cross))));

        assertThat(summaryLine(html, "Coverage per domain"))
                .contains("currency: 1 scenario")
                .contains("weather: 2 scenarios");
    }

    @Test
    @DisplayName("a multi-turn run shows every turn's message, trajectory and answer")
    void showsEveryTurn() {
        var scenario = new Scenario("weather-multi-turn-memory", List.of("weather"), "multi-turn", "synthetic",
                List.of("Vai chover amanhã em São Paulo?", "De qual cidade eu perguntei?"),
                new Scenario.Expectation(WEATHER.expect().trajectory(), null, List.of(
                        new Scenario.TurnExpectation(2, null,
                                new Scenario.AnswerExpectation(null, List.of("São Paulo"), null)))),
                List.of("get_weather"), false, null);
        var firstTools = new Trajectory("ANSWERED", List.of(), List.of(
                new ToolCall("get_weather", "{\"latitude\":\"-23.55\"}", "{\"precipitation_sum\":[0.0,12.4]}")));
        var secondTools = new Trajectory("ANSWERED", List.of(), List.of());
        var run = new ScenarioResult(scenario, firstTools, "Você perguntou sobre <São Paulo>.",
                List.of(new CheckResult("turn 2: contains: São Paulo", true, "found")),
                Duration.ofMillis(900), 10, 5, Cost.NONE, false, List.of(
                new ScenarioResult.TurnResult(1, "Vai chover amanhã em São Paulo?", "conv-1", firstTools,
                        "Sim: 12,4 mm."),
                new ScenarioResult.TurnResult(2, "De qual cidade eu perguntei?", "conv-1", secondTools,
                        "Você perguntou sobre <São Paulo>.")));

        var html = render(List.of(once(run)));

        assertThat(html)
                .contains("Turn 1 of 2")
                .contains("Turn 2 of 2")
                .contains("Sim: 12,4 mm.")
                .contains("Você perguntou sobre &lt;São Paulo&gt;.")
                .contains("De qual cidade eu perguntei?")
                .contains("conv-1")
                .contains("Expected at turn 2")
                .contains("turn 2: contains: São Paulo");
        assertThat(html.indexOf("Turn 1 of 2")).isLessThan(html.indexOf("Sim: 12,4 mm."));
        assertThat(html.indexOf("Sim: 12,4 mm.")).isLessThan(html.indexOf("Turn 2 of 2"));
    }

    @Test
    @DisplayName("a multi-turn run that stopped after turn 1 still shows turn 1 in the per-turn layout")
    void showsTheTurnsOfAnInterruptedRun() {
        var scenario = new Scenario("weather-multi-turn-memory", List.of("weather"), "multi-turn", "synthetic",
                List.of("Vai chover amanhã em São Paulo?", "De qual cidade eu perguntei?"),
                WEATHER.expect(), List.of("get_weather"), false, null);
        var first = new Trajectory("ANSWERED", List.of(), List.of());
        var run = new ScenarioResult(scenario, first, null,
                List.of(new CheckResult("request", false, "turn 2: POST /api/chat answered HTTP 500")),
                Duration.ofMillis(900), 0, 0, Cost.NONE, false, List.of(
                new ScenarioResult.TurnResult(1, "Vai chover amanhã em São Paulo?", "conv-1", first, "Sim.")));

        var html = render(List.of(once(run)));

        assertThat(html).contains("Turn 1 of 2").contains("<pre>Sim.</pre>");
    }

    @Test
    @DisplayName("the report lists coverage per domain with each shortfall of the floor")
    void rendersCoveragePerDomain() {
        var coverage = new ScenarioCoverage(List.of(
                new ScenarioCoverage.DomainCoverage("weather", 4, 1, 3, 0, true,
                        List.of("1 happy path of the 2 required", "no multi-turn scenario, and the domain holds state")),
                new ScenarioCoverage.DomainCoverage("cnpj<b>", 5, 2, 3, 0, false, List.of())),
                List.of(), List.of());

        var html = EvalReportRenderer.render(List.of(once(passing(WEATHER))), coverage, EVERYTHING);

        assertThat(html)
                .contains("<h2>Coverage per domain</h2>")
                .contains("<tr><td>weather</td><td>4</td><td>1</td><td>3</td><td>0</td><td>yes</td>"
                        + "<td><span class=\"fail\">below the floor</span>: 1 happy path of the 2 required; "
                        + "no multi-turn scenario, and the domain holds state</td></tr>")
                .contains("<tr><td>cnpj&lt;b&gt;</td><td>5</td><td>2</td><td>3</td><td>0</td><td>no</td>"
                        + "<td><span class=\"pass\">meets the floor</span></td></tr>");
    }

    @Test
    @DisplayName("the report lists every artifact no scenario depends on, and every dependsOn that names nothing")
    void rendersUncoveredArtifacts() {
        var coverage = new ScenarioCoverage(List.of(), List.of(
                new ArtifactInventory.Artifact(ArtifactInventory.Kind.TOOL, "lookup_company_by_cnpj"),
                new ArtifactInventory.Artifact(ArtifactInventory.Kind.SUB_AGENT, "weather_reporter")),
                List.of("w9 depends on 'get_wether'"));

        var html = EvalReportRenderer.render(List.of(), coverage, EVERYTHING);

        assertThat(html)
                .contains("<h2>Uncovered artifacts</h2>")
                .contains("<li>tool <code>lookup_company_by_cnpj</code></li>")
                .contains("<li>sub-agent <code>weather_reporter</code></li>")
                .contains("<h2>Unknown dependencies</h2>")
                .contains("<li>w9 depends on &#39;get_wether&#39;</li>");
    }

    @Test
    @DisplayName("with every artifact covered and every dependency known, the report says so")
    void rendersFullCoverage() {
        var html = EvalReportRenderer.render(List.of(), ScenarioCoverage.empty(), EVERYTHING);

        assertThat(html)
                .contains("<p>none: every artifact is named by at least one scenario</p>")
                .doesNotContain("Unknown dependencies");
    }

    @Test
    @DisplayName("the summary names what the run was narrowed to")
    void rendersTheSelection() {
        var selection = new ScenarioSelection("weather", null, "main", Set.of("get_weather"));

        var html = EvalReportRenderer.render(List.of(), ScenarioCoverage.empty(), selection);

        assertThat(summaryLine(html, "Selection"))
                .contains("domain weather; depends on an artifact changed since main: [get_weather]");
    }

    private static final ScenarioSelection EVERYTHING = ScenarioSelection.everything();

    private static String render(List<ScenarioRuns> runs) {
        return EvalReportRenderer.render(runs, ScenarioCoverage.empty(), EVERYTHING);
    }

    /** The one summary row that starts with {@code label}, so an assertion cannot match elsewhere. */
    private static String summaryLine(String html, String label) {
        return html.lines()
                .filter(line -> line.startsWith("<li><strong>" + label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no summary line " + label + " in:\n" + html));
    }
}
