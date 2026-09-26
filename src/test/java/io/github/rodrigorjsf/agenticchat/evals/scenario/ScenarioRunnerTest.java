package io.github.rodrigorjsf.agenticchat.evals.scenario;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.testsupport.RecordingAgentTracer;
import io.github.rodrigorjsf.agenticchat.testsupport.StubChatModelRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runner, driven through the real HTTP endpoint with a scripted model and no network.
 *
 * <p>The row under test is the committed weather row itself, so this test and the
 * real-model eval disagree only about who answers — the script here, the provider there.
 * The weather API is the local stub, reached through the same catalogue key the tool uses.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioRunnerTest {

    private static final String IN_SCOPE = """
            {"decision":"IN_SCOPE","confidence":0.95,"intent":"DATA_REQUEST","language":"pt-BR",
             "skillHint":"","riskFlags":[]}""";

    private EmbeddedServer upstream;
    private EmbeddedServer app;
    private StubChatModelRegistry models;
    private ScenarioRunner runner;
    private Scenario weather;

    @BeforeAll
    void startUpstreamStub() {
        upstream = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "stub.api.enabled", "true",
                "micronaut.server.port", -1));
        weather = ScenarioDataset.loadCommitted().stream()
                .filter(s -> s.id().equals("weather-happy-forecast-tomorrow"))
                .findFirst()
                .orElseThrow();
    }

    @BeforeEach
    void startApp() {
        Map<String, Object> config = new HashMap<>();
        config.put("micronaut.server.port", -1);
        config.put("agentic.test.stub-models", "true");
        config.put("agentic.test.recording-agent-tracer", "true");
        config.put("agentic.guardrails.input.llm-classifier-enabled", "false");
        config.put("agentic.tools.apis.open-meteo-forecast.base-url",
                "http://localhost:" + upstream.getPort() + "/stub/open-meteo");
        app = ApplicationContext.run(EmbeddedServer.class, config);
        models = (StubChatModelRegistry) app.getApplicationContext().getBean(ChatModelRegistry.class);
        runner = new ScenarioRunner(URI.create("http://localhost:" + app.getPort()),
                app.getApplicationContext().getBean(RecordingAgentTracer.class));
    }

    @AfterEach
    void stopApp() {
        if (app != null) {
            app.close();
        }
    }

    @AfterAll
    void stopUpstreamStub() {
        if (upstream != null) {
            upstream.close();
        }
    }

    @Test
    @DisplayName("a trajectory that activates the skill and calls get_weather passes, and the report data is captured")
    void theScriptedTrajectoryPasses() {
        models.model("judge").fallbackTo(IN_SCOPE);
        var agent = models.model("agent");
        agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("call-1").name("activate_skill")
                .arguments("{\"skill_name\":\"geo-and-weather\"}").build()));
        agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("call-2").name("get_weather")
                .arguments("{\"latitude\":\"-23.55\",\"longitude\":\"-46.63\",\"forecastDays\":\"2\"}").build()));
        agent.replyWith("Sim, amanhã deve chover em São Paulo: 12,4 mm previstos.");

        var result = runner.run(weather);

        assertThat(result.checks()).allSatisfy(check -> assertThat(check.passed())
                .as("%s: %s", check.name(), check.reason()).isTrue());
        assertThat(result.passed()).isTrue();
        assertThat(result.trajectory().outcome()).isEqualTo("ANSWERED");
        assertThat(result.trajectory().activations()).containsExactly("geo-and-weather");

        var getWeather = result.trajectory().toolCalls().stream()
                .filter(call -> call.name().equals("get_weather"))
                .findFirst()
                .orElseThrow();
        assertThat(getWeather.arguments()).contains("-23.55");
        assertThat(getWeather.result())
                .as("the result is what the upstream stub actually returned")
                .contains("12.4");

        assertThat(result.answer()).contains("12,4 mm");
        assertThat(result.latency()).isPositive();
        assertThat(result.inputTokens()).isPositive();
        assertThat(result.outputTokens()).isPositive();
    }

    @Test
    @DisplayName("a trajectory that answers without calling get_weather fails, and the reason names the missing tool")
    void aWrongTrajectoryFailsWithAReason() {
        models.model("judge").fallbackTo(IN_SCOPE);
        models.model("agent").replyWith("Acho que vai chover amanhã em São Paulo.");

        var result = runner.run(weather);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks())
                .filteredOn(check -> !check.passed())
                .singleElement()
                .satisfies(check -> assertThat(check.reason()).contains("get_weather"));
    }

    @Test
    @DisplayName("a refused turn fails the outcome check, and the reason names both outcomes")
    void aWrongOutcomeFailsWithAReason() {
        models.model("judge").fallbackTo("""
                {"decision":"OUT_OF_SCOPE","confidence":0.95,"intent":"OFF_TOPIC","language":"pt-BR",
                 "skillHint":"","riskFlags":[]}""");

        var result = runner.run(weather);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks())
                .filteredOn(check -> check.name().equals("outcome"))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.passed()).isFalse();
                    assertThat(check.reason()).contains("ANSWERED").contains("REFUSED");
                });
    }

    @Test
    @DisplayName("a turn the provider refuses on quota is SKIPPED, not failed")
    void aRateLimitedTurnIsSkipped() {
        models.model("judge").fallbackTo(IN_SCOPE);
        models.model("agent").reply(request -> {
            throw new IllegalStateException("429 RESOURCE_EXHAUSTED: quota exceeded for this model");
        });

        var result = runner.run(weather);

        assertThat(result.skipped()).isTrue();
        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).singleElement()
                .satisfies(check -> assertThat(check.reason()).contains("RESOURCE_EXHAUSTED"));
        assertThat(result.cost().unpricedCalls())
                .as("the calls made before the quota error still count toward the estimate")
                .isPositive();
    }

    @Test
    @DisplayName("a server error that is not a rate limit is still a failure")
    void anyOtherServerErrorIsAFailure() {
        models.model("judge").fallbackTo(IN_SCOPE);
        models.model("agent").reply(request -> {
            throw new IllegalStateException("missing thought_signature");
        });

        var result = runner.run(weather);

        assertThat(result.skipped()).isFalse();
        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).singleElement()
                .satisfies(check -> assertThat(check.reason()).contains("HTTP 500"));
    }

    @Test
    @DisplayName("each repetition is its own run: one right and one wrong trajectory make a flaky scenario")
    void eachRepetitionIsItsOwnRun() {
        models.model("judge").fallbackTo(IN_SCOPE);
        var agent = models.model("agent");
        agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("call-1").name("get_weather")
                .arguments("{\"latitude\":\"-23.55\",\"longitude\":\"-46.63\",\"forecastDays\":\"2\"}").build()));
        agent.replyWith("Sim, amanhã deve chover em São Paulo: 12,4 mm previstos.");
        agent.replyWith("Acho que vai chover amanhã em São Paulo.");
        var pauses = new java.util.concurrent.atomic.AtomicInteger();

        var runs = runner.repeat(weather, 2, pauses::incrementAndGet);

        assertThat(runs.repetitions()).hasSize(2);
        assertThat(runs.repetitions().get(0).passed()).isTrue();
        assertThat(runs.repetitions().get(1).passed()).isFalse();
        assertThat(runs.status()).isEqualTo(ScenarioRuns.Status.FLAKY);
        assertThat(pauses).as("paced between repetitions, not after the last").hasValue(1);
    }

    @Test
    @DisplayName("the cost of a run is read from the tracer, and a model with no price is counted rather than priced at zero")
    void costComesFromTheTracer() {
        models.model("judge").fallbackTo(IN_SCOPE);
        models.model("agent").replyWith("Acho que vai chover amanhã em São Paulo.");

        var result = runner.run(weather);

        assertThat(result.cost().usd()).isZero();
        assertThat(result.cost().unpricedCalls())
                .as("the scripted models carry no model name, so no call is priced")
                .isPositive();
    }
}
