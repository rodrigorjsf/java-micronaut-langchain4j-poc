package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.testsupport.RecordingAgentTracer;
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

/**
 * The committed scenarios, run end to end against the real model and the real upstream APIs.
 *
 * <p>Tagged {@code evals}: it needs an API key, costs money and is subject to rate limits, so
 * it runs with {@code ./mvnw test -Pevals} and never in the default build. Each turn goes
 * through {@code POST /api/chat} on an embedded server; the trajectory comes back through the
 * recording {@code AgentTracer}.
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
    private final List<ScenarioResult> results = new ArrayList<>();

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

    private static ScenarioRunner runnerFor(EmbeddedServer server) {
        return new ScenarioRunner(URI.create("http://localhost:" + server.getPort()),
                server.getApplicationContext().getBean(RecordingAgentTracer.class));
    }

    /** A row with an upstream failure gets a context of its own, closed as soon as it has run. */
    private ScenarioResult run(Scenario scenario) {
        if (scenario.upstream() == null) {
            return runner.run(scenario);
        }
        var stubServer = URI.create("http://localhost:" + stub.getPort());
        try (var faked = startApp(scenario.upstream().properties(stubServer))) {
            return runnerFor(faked).run(scenario);
        }
    }

    @AfterAll
    void writeReportAndStop() throws IOException {
        try {
            Files.writeString(REPORT, EvalReportRenderer.render(results), StandardCharsets.UTF_8);
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
    @DisplayName("scenario suite: every critical scenario passes against the real model")
    void everyCriticalScenarioPasses() {
        var scenarios = ScenarioDataset.loadCommitted();
        assertThat(scenarios).isNotEmpty();

        for (int i = 0; i < scenarios.size(); i++) {
            if (i > 0) {
                pace();
            }
            var result = run(scenarios.get(i));
            results.add(result);
            System.out.printf("  %s %s%n", result.passed() ? "PASS" : "FAIL", result.scenario().id());
        }

        assertThat(results)
                .filteredOn(result -> result.scenario().critical())
                .allSatisfy(result -> assertThat(result.passed())
                        .as("%s: %s", result.scenario().id(), result.checks())
                        .isTrue());
    }

    private static void pace() {
        try {
            Thread.sleep(PACING_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
