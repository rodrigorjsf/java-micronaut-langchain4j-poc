package io.github.rodrigorjsf.agenticchat.agent.workflow;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.testsupport.StubChatModelRegistry;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The composed workflow: sequence, then a parallel stage, then a summary.
 *
 * <p>Routing by request rather than by position, because the middle stage runs its
 * two sub-agents concurrently and a positional script would assume an order that
 * does not exist.
 */
class TripBriefingWorkflowTest {

    private ApplicationContext ctx;
    private StubChatModelRegistry models;
    private TripBriefingWorkflow workflow;

    @BeforeEach
    void setUp() {
        Map<String, Object> config = new HashMap<>();
        config.put("agentic.test.stub-models", "true");
        config.put("agentic.llm.credentials.google-api-key", "fake");
        config.put("agentic.llm.credentials.openai-api-key", "fake");
        ctx = ApplicationContext.run(config);
        models = (StubChatModelRegistry) ctx.getBean(ChatModelRegistry.class);
        workflow = ctx.getBean(TripBriefingWorkflow.class);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private static String systemTextOf(ChatRequest request) {
        return request.messages().stream()
                .filter(dev.langchain4j.data.message.SystemMessage.class::isInstance)
                .map(m -> ((dev.langchain4j.data.message.SystemMessage) m).text())
                .findFirst()
                .orElse("");
    }

    private void scriptTheFourSubAgents() {
        models.model("agent").routeBy(request -> {
            String system = systemTextOf(request);
            if (system.contains("resolve a place name")) {
                return AiMessage.from("Florianópolis, Santa Catarina, Brasil | -27.5954 | -48.548");
            }
            if (system.contains("report weather")) {
                return AiMessage.from("Sol entre nuvens, máxima de 24 graus e mínima de 18.");
            }
            if (system.contains("national holidays")) {
                return AiMessage.from("Sim, 7 de setembro é a Independência do Brasil.");
            }
            return AiMessage.from("""
                    {"place":"Florianópolis, Santa Catarina, Brasil",
                     "weather":"Sol entre nuvens, máxima de 24 graus.",
                     "holiday":"Sim, Independência do Brasil.",
                     "advice":"Leve protetor solar e espere praias cheias."}""");
        });
    }

    @Test
    @DisplayName("the workflow runs all four sub-agents and returns a structured briefing")
    void producesAStructuredBriefing() {
        scriptTheFourSubAgents();

        var briefing = workflow.brief("Florianopolis", "2026-09-07");

        assertThat(briefing.place()).contains("Florianópolis");
        assertThat(briefing.weather()).contains("Sol");
        assertThat(briefing.holiday()).contains("Independência");
        assertThat(briefing.advice()).isNotBlank();
        assertThat(models.model("agent").callCount())
                .as("resolve, weather, holiday, brief")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("each sub-agent is asked only what it needs")
    void subAgentsAreGivenIsolatedContexts() {
        scriptTheFourSubAgents();

        workflow.brief("Florianopolis", "2026-09-07");

        var requests = models.model("agent").requests();
        var brieferRequest = requests.stream()
                .filter(request -> systemTextOf(request).contains("compose a short travel briefing"))
                .findFirst()
                .orElseThrow();

        assertThat(brieferRequest.toolSpecifications())
                .as("the summariser has no tools; it only reads what the others wrote")
                .isNullOrEmpty();

        var resolverRequest = requests.stream()
                .filter(request -> systemTextOf(request).contains("resolve a place name"))
                .findFirst()
                .orElseThrow();
        assertThat(resolverRequest.messages().toString())
                .as("the resolver never sees the holiday question")
                .doesNotContain("holiday");
    }

    @Test
    @DisplayName("the raw tool output never reaches the caller — that is the point")
    void theCallerReceivesASummaryNotRawJson() {
        scriptTheFourSubAgents();

        var briefing = workflow.brief("Florianopolis", "2026-09-07");

        assertThat(briefing.toString())
                .doesNotContain("latitude")
                .doesNotContain("weather_code")
                .doesNotContain("{\"");
    }
}
