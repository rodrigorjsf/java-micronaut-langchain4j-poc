package io.github.rodrigorjsf.agenticchat.skills;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.github.rodrigorjsf.agenticchat.conversation.ChatTurnService;
import io.github.rodrigorjsf.agenticchat.memory.ConversationId;
import io.github.rodrigorjsf.agenticchat.testsupport.StubChatModelRegistry;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single assertion the whole 50-tool design rests on: after the model calls
 * {@code activate_skill}, that skill's tools are present in the <em>next</em>
 * request, and nothing else is.
 *
 * <p>Worth its own class because the mechanism is indirect and fails silently.
 * LangChain4j reconstructs the visible tool set on every round trip by scanning
 * chat memory for a {@code ToolExecutionResultMessage} carrying the activation
 * attribute. If that attribute is dropped — by a memory store that persists only
 * message text, or by the activation result sliding out of the window — the tools
 * simply go hidden again. No exception, no log line: the agent just stops being
 * able to do anything, and the only symptom is a model that answers from memory.
 */
class SkillActivationTest {

    private ApplicationContext ctx;
    private StubChatModelRegistry models;
    private ChatTurnService turns;

    @BeforeEach
    void setUp() {
        Map<String, Object> config = new HashMap<>();
        config.put("agentic.test.stub-models", "true");
        config.put("agentic.llm.credentials.google-api-key", "fake");
        config.put("agentic.llm.credentials.openai-api-key", "fake");
        config.put("agentic.guardrails.input.llm-classifier-enabled", "false");
        ctx = ApplicationContext.run(config);
        models = (StubChatModelRegistry) ctx.getBean(
                io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry.class);
        turns = ctx.getBean(ChatTurnService.class);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private static String inScope() {
        return """
                {"decision":"IN_SCOPE","confidence":0.95,"intent":"cep_lookup","language":"pt-BR",
                 "skillHint":"","riskFlags":[],"outOfScopeReply":""}""";
    }

    private static List<String> toolNames(ChatRequest request) {
        return request.toolSpecifications().stream().map(ToolSpecification::name).toList();
    }

    @Test
    @DisplayName("a skill's tools appear only after activate_skill, and only that skill's")
    void activatingASkillDisclosesItsToolsAndNoOthers() {
        models.model("judge").replyWith(inScope());

        var agent = models.model("agent");
        agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("call-1")
                .name("activate_skill")
                .arguments("{\"skill_name\":\"brazil-civic-data\"}")
                .build()));
        agent.replyWith("O CEP da Avenida Paulista é 01310-100.");

        var turn = turns.handle(ConversationId.newId(), "qual o cep da avenida paulista 1578");

        assertThat(agent.callCount())
                .as("one round trip to activate, one to answer")
                .isEqualTo(2);

        var before = toolNames(agent.requests().get(0));
        var after = toolNames(agent.requests().get(1));

        assertThat(before)
                .as("nothing but the skill-management tools before activation")
                .contains("activate_skill")
                .doesNotContain("lookup_cep", "get_weather");

        assertThat(after)
                .as("THE assertion: the activated skill's tools are now visible")
                .contains("activate_skill", "lookup_cep", "lookup_ddd", "list_national_holidays");

        assertThat(after)
                .as("activating one skill must not disclose another's tools")
                .doesNotContain("get_weather", "find_place");

        assertThat(turn.reply()).contains("01310-100");
    }

    @Test
    @DisplayName("activating a second skill adds to the first, it does not replace it")
    void skillsAccumulateWithinAConversation() {
        models.model("judge").fallbackTo(inScope());

        var agent = models.model("agent");
        agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("a").name("activate_skill")
                .arguments("{\"skill_name\":\"brazil-civic-data\"}").build()));
        agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("b").name("activate_skill")
                .arguments("{\"skill_name\":\"geo-and-weather\"}").build()));
        agent.replyWith("Vou consultar as duas fontes.");

        turns.handle(ConversationId.newId(), "qual o cep da paulista e vai chover la?");

        var last = toolNames(agent.requests().getLast());
        assertThat(last).contains("lookup_cep", "get_weather", "find_place");
    }

    @Test
    @DisplayName("activation survives into the next user turn of the same conversation")
    void activationSurvivesAcrossTurns() {
        models.model("judge").fallbackTo(inScope());

        var agent = models.model("agent");
        agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("a").name("activate_skill")
                .arguments("{\"skill_name\":\"geo-and-weather\"}").build()));
        agent.replyWith("Vai chover amanhã.");
        agent.replyWith("Hoje faz sol.");

        var id = ConversationId.newId();
        turns.handle(id, "vai chover amanha em floripa?");
        turns.handle(id, "e hoje, como esta o tempo?");

        assertThat(toolNames(agent.requests().getLast()))
                .as("the activation result is still in the window, so the tools are still visible")
                .contains("get_weather", "find_place");
    }

    @Test
    @DisplayName("activation is lost once the result slides out of the memory window")
    void activationIsLostWhenTheWindowEvictsTheActivationResult() {
        // Documents a real property of the mechanism rather than asserting a wish:
        // visibility is reconstructed from chat memory on every round trip, so a
        // long conversation eventually re-hides the tools and the model has to
        // activate again. A small window makes that observable in one test.
        ctx.close();
        Map<String, Object> config = new HashMap<>();
        config.put("agentic.test.stub-models", "true");
        config.put("agentic.llm.credentials.google-api-key", "fake");
        config.put("agentic.llm.credentials.openai-api-key", "fake");
        config.put("agentic.guardrails.input.llm-classifier-enabled", "false");
        config.put("agentic.agent.memory-window-messages", 4);
        ctx = ApplicationContext.run(config);
        models = (StubChatModelRegistry) ctx.getBean(
                io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry.class);
        turns = ctx.getBean(ChatTurnService.class);

        models.model("judge").fallbackTo(inScope());
        var agent = models.model("agent");
        agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("a").name("activate_skill")
                .arguments("{\"skill_name\":\"geo-and-weather\"}").build()));
        agent.fallbackTo("ok");

        var id = ConversationId.newId();
        turns.handle(id, "vai chover amanha em floripa?");
        turns.handle(id, "e depois de amanha na capital paulista?");
        turns.handle(id, "e no fim de semana em salvador por favor");

        assertThat(toolNames(agent.requests().getLast()))
                .as("with a 4-message window the activation has been evicted and the model must re-activate")
                .doesNotContain("get_weather");
    }
}
