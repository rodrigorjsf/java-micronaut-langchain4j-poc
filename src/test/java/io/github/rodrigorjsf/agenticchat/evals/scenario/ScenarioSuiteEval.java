package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.testsupport.RecordingAgentTracer;
import io.github.rodrigorjsf.agenticchat.tools.http.LinkPolicy;
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
    private ScenarioRunner runner;
    private final List<ScenarioResult> results = new ArrayList<>();

    @BeforeAll
    void startApp() {
        app = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "micronaut.server.port", -1,
                "agentic.test.recording-agent-tracer", "true",
                // The report shows each tool call's arguments and result, which the tool
                // listener only records while content capture is on. Pinned here so a
                // deployment-level switch cannot silently empty the report.
                "agentic.observability.capture-content", "true"));
        runner = new ScenarioRunner(URI.create("http://localhost:" + app.getPort()),
                app.getApplicationContext().getBean(RecordingAgentTracer.class),
                app.getApplicationContext().getBean(LinkPolicy.class));
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
            var result = runner.run(scenarios.get(i));
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
