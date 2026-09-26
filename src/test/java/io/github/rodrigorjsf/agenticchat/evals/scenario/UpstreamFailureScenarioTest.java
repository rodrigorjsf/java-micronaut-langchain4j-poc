package io.github.rodrigorjsf.agenticchat.evals.scenario;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.testsupport.RecordingAgentTracer;
import io.github.rodrigorjsf.agenticchat.testsupport.StubChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each committed upstream-failure row, run with the override the row declares: its catalogue
 * key pointed at the local stub's failure route, the model scripted, nothing on the network.
 *
 * <p>What is asserted is what the model is handed after the tool door has turned the failure
 * into a value — the text it has to answer the user from.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpstreamFailureScenarioTest {

    private static final String IN_SCOPE = """
            {"decision":"IN_SCOPE","confidence":0.95,"intent":"DATA_REQUEST","language":"pt-BR",
             "skillHint":"","riskFlags":[]}""";

    private EmbeddedServer stub;

    @BeforeAll
    void startStub() {
        stub = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "stub.api.enabled", "true",
                "micronaut.server.port", -1));
    }

    @AfterAll
    void stopStub() {
        if (stub != null) {
            stub.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the row's own override fakes its upstream, and the model is told what failed")
    @CsvSource(delimiter = '|', value = {
            "weather-upstream-server-error | The service is unavailable",
            "weather-upstream-timeout      | The service is unavailable",
            "weather-upstream-oversized    | [truncated: the response was longer than this tool's budget"})
    void theDeclaredFailureReachesTheModel(String id, String toldToTheModel) {
        var scenario = ScenarioDataset.loadCommitted().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow();

        var catalogue = stub.getApplicationContext().getBean(ToolHttpClient.class).knownApis();
        Map<String, Object> config = new HashMap<>(scenario.upstream().properties(
                URI.create("http://localhost:" + stub.getPort()), catalogue));
        config.put("micronaut.server.port", -1);
        config.put("agentic.test.stub-models", "true");
        config.put("agentic.test.recording-agent-tracer", "true");
        config.put("agentic.guardrails.input.llm-classifier-enabled", "false");
        try (var app = ApplicationContext.run(EmbeddedServer.class, config)) {
            var models = (StubChatModelRegistry) app.getApplicationContext().getBean(ChatModelRegistry.class);
            models.model("judge").fallbackTo(IN_SCOPE);
            var agent = models.model("agent");
            agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                    .id("call-1").name("activate_skill")
                    .arguments("{\"skill_name\":\"geo-and-weather\"}").build()));
            agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                    .id("call-2").name("get_weather")
                    .arguments("{\"latitude\":\"-25.43\",\"longitude\":\"-49.27\",\"forecastDays\":\"2\"}").build()));
            agent.replyWith("A previsão do tempo está indisponível no momento.");

            var result = new ScenarioRunner(URI.create("http://localhost:" + app.getPort()),
                    app.getApplicationContext().getBean(RecordingAgentTracer.class)).run(scenario);

            assertThat(result.passed()).as("%s", result.checks()).isTrue();
            assertThat(result.trajectory().toolCalls())
                    .filteredOn(call -> call.name().equals("get_weather"))
                    .singleElement()
                    .satisfies(call -> assertThat(call.result()).contains(toldToTheModel));
        }
    }
}
