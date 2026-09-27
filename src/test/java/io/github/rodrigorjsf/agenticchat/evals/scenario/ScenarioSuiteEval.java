package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.testsupport.RecordingAgentTracer;
import io.github.rodrigorjsf.agenticchat.tools.http.LinkPolicy;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The committed scenarios, run end to end against the real model and the real upstream APIs.
 *
 * <p>Tagged {@code evals}: it needs an API key, costs money and is subject to rate limits, so
 * it runs with {@code ./mvnw test -Pevals} and never in the default build. Each turn goes
 * through {@code POST /api/chat} on an embedded server; the trajectory comes back through the
 * recording {@code AgentTracer}.
 *
 * <p>Each scenario runs {@value ScenarioRuns#DEFAULT_REPETITIONS} times — override with
 * {@code -Dscenarios.repetitions=N}. A {@code critical} scenario must pass every repetition that
 * ran; any other scenario reports its pass rate and never fails the eval. A repetition the
 * provider rate-limited is SKIPPED, and when no repetition ran at all the eval is aborted
 * (reported as skipped) rather than passed or failed.
 *
 * <p>A row's optional {@code rubric} is scored by the {@code grader} model role after every
 * deterministic check. Its verdicts are uncalibrated: they appear in the report and never fail
 * the eval.
 *
 * <p>A row that declares an {@code upstream} failure runs in a context of its own, with that
 * one catalogue key pointed at the local {@code StubApiController}; the model and every other
 * upstream stay real, and the shared context never sees the override.
 *
 * <p>{@code eval-report.html} is written at the repository root after the run — in
 * {@link AfterAll}, so a failing gate still leaves the page that explains it. The file is
 * gitignored.
 */
@Tag("evals")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioSuiteEval {

    /** Surefire runs with the project base directory as the working directory: the repository root. */
    private static final Path REPORT = Path.of(System.getProperty("user.dir"), "eval-report.html");

    /** Below ~6 requests/minute, inside the measured free-tier limit (see TriageGoldenSetEval). */
    private static final long PACING_MILLIS = 10_000;

    private EmbeddedServer app;
    private EmbeddedServer stub;
    private ScenarioRunner runner;
    private final List<ScenarioRuns> scenarioRuns = new ArrayList<>();

    @BeforeAll
    void startApp() {
        app = startApp(Map.of());
        runner = runnerFor(app);
        stub = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "stub.api.enabled", "true",
                "micronaut.server.port", -1));
    }

    private static EmbeddedServer startApp(Map<String, Object> overrides) {
        Map<String, Object> config = new HashMap<>(overrides);
        config.put("micronaut.server.port", -1);
        config.put("agentic.test.recording-agent-tracer", "true");
        // The report shows each tool call's arguments and result, which the tool
        // listener only records while content capture is on. Pinned here so a
        // deployment-level switch cannot silently empty the report.
        config.put("agentic.observability.capture-content", "true");
        return ApplicationContext.run(EmbeddedServer.class, config);
    }

    /**
     * The rubric is scored by the registry's {@code grader} role, which the registry refuses to
     * start with when it is the agent's model.
     */
    private static ScenarioRunner runnerFor(EmbeddedServer server) {
        var context = server.getApplicationContext();
        return new ScenarioRunner(URI.create("http://localhost:" + server.getPort()),
                context.getBean(RecordingAgentTracer.class),
                context.getBean(LinkPolicy.class),
                new RubricGrader(context.getBean(ChatModelRegistry.class).forRole("grader")));
    }

    /**
     * A row with an upstream failure gets a context of its own, shared by its repetitions and
     * closed as soon as they have run.
     */
    private ScenarioRuns repeat(Scenario scenario, int repetitions) {
        if (scenario.upstream() == null) {
            return runner.repeat(scenario, repetitions, ScenarioSuiteEval::pace);
        }
        var stubServer = URI.create("http://localhost:" + stub.getPort());
        var catalogue = app.getApplicationContext().getBean(ToolHttpClient.class).knownApis();
        try (var faked = startApp(scenario.upstream().properties(stubServer, catalogue))) {
            return runnerFor(faked).repeat(scenario, repetitions, ScenarioSuiteEval::pace);
        }
    }

    @AfterAll
    void writeReportAndStop() throws IOException {
        try {
            Files.writeString(REPORT, EvalReportRenderer.render(scenarioRuns), StandardCharsets.UTF_8);
            System.out.printf("%nscenario suite: report written to %s%n", REPORT);
        } finally {
            if (app != null) {
                app.close();
            }
            if (stub != null) {
                stub.close();
            }
        }
    }

    @Test
    @DisplayName("scenario suite: every critical scenario passes every repetition against the real model")
    void everyCriticalScenarioPasses() {
        var scenarios = ScenarioDataset.loadCommitted();
        assertThat(scenarios).isNotEmpty();
        int repetitions = ScenarioRuns.parseRepetitions(System.getProperty(ScenarioRuns.REPETITIONS_PROPERTY));

        for (int i = 0; i < scenarios.size(); i++) {
            if (i > 0) {
                pace();
            }
            var runs = repeat(scenarios.get(i), repetitions);
            scenarioRuns.add(runs);
            System.out.printf("  %s %s (%d/%d passed, %d skipped)%n", runs.status(), runs.scenario().id(),
                    runs.passes(), runs.executed(), runs.skipped());
        }

        assumeTrue(scenarioRuns.stream().anyMatch(runs -> runs.executed() > 0),
                "every repetition was rate limited: nothing was measured");
        assertThat(scenarioRuns)
                .filteredOn(ScenarioRuns::failsGate)
                .as("critical scenarios that failed a repetition")
                .isEmpty();
    }

    private static void pace() {
        try {
            Thread.sleep(PACING_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
