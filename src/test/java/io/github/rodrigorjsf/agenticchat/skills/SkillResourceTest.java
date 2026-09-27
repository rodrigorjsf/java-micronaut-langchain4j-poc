package io.github.rodrigorjsf.agenticchat.skills;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.skills.ClassPathSkillLoader;
import dev.langchain4j.skills.Skill;
import dev.langchain4j.skills.SkillResource;
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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The third tier of progressive disclosure: a skill's resources, read on demand with
 * {@code read_skill_resource}.
 *
 * <p>LangChain4j registers that tool only when at least one loaded skill ships a
 * resource file, so a catalogue of single-file skills never exercises it. This test
 * drives a real turn through the stub model and checks what the model would see:
 * the tool offered, and the reference text coming back as its result.
 */
class SkillResourceTest {

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
                {"decision":"IN_SCOPE","confidence":0.95,"intent":"DATA_REQUEST","language":"pt-BR",
                 "skillHint":"","riskFlags":[]}""";
    }

    private static List<String> toolNames(ChatRequest request) {
        return request.toolSpecifications().stream().map(ToolSpecification::name).toList();
    }

    private static ToolExecutionResultMessage lastToolResult(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        return (ToolExecutionResultMessage) messages.getLast();
    }

    @Test
    @DisplayName("read_skill_resource is offered and returns the reference a skill body routes to")
    void readSkillResourceReturnsTheReference() {
        models.model("judge").fallbackTo(inScope());

        var agent = models.model("agent");
        agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("a").name("activate_skill")
                .arguments("{\"skill_name\":\"health-and-food\"}").build()));
        agent.reply(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("b").name("read_skill_resource")
                .arguments("{\"skill_name\":\"health-and-food\","
                        + "\"relative_path\":\"references/packaged-food.md\"}").build()));
        agent.replyWith("Open Food Facts lista 539 kcal por 100 g.");

        turns.handle(ConversationId.newId(), "quantas calorias tem a nutella?");

        assertThat(agent.callCount()).isEqualTo(3);
        assertThat(toolNames(agent.requests().getFirst()))
                .as("the third-tier tool is registered from the first round trip")
                .contains("activate_skill", "read_skill_resource");

        var result = lastToolResult(agent.requests().get(2));
        assertThat(result.toolName()).isEqualTo("read_skill_resource");
        assertThat(result.text())
                .as("the reference body, not an error string")
                .contains("per 100 grams")
                .contains("allergens_tags");
    }

    private static final Pattern RESOURCE_CALL =
            Pattern.compile("read_skill_resource\\(\"([^\"]+)\", \"([^\"]+)\"\\)");

    /**
     * The resource path is model-visible text matched by exact string equality, so a
     * body that names a path the loader did not produce sends the model on a round
     * trip that ends in an error. And a resource no body routes to is never read.
     */
    @Test
    @DisplayName("every resource a skill body names exists, and every resource is named by its body")
    void skillBodiesAndResourcesAgree() {
        List<Skill> skills = ClassPathSkillLoader.loadSkills("skills");
        int routed = 0;

        for (Skill skill : skills) {
            var named = new java.util.TreeSet<String>();
            var matcher = RESOURCE_CALL.matcher(skill.content());
            while (matcher.find()) {
                assertThat(matcher.group(1))
                        .as("a resource call in '%s' must name its own skill", skill.name())
                        .isEqualTo(skill.name());
                named.add(matcher.group(2));
            }
            var shipped = new java.util.TreeSet<String>();
            skill.resources().stream().map(SkillResource::relativePath).forEach(shipped::add);

            assertThat(named).as("resource paths named in the body of '%s'", skill.name())
                    .isEqualTo(shipped);
            routed += named.size();
        }

        assertThat(routed)
                .as("at least one runtime skill routes to a reference, or read_skill_resource is never registered")
                .isPositive();
    }
}
