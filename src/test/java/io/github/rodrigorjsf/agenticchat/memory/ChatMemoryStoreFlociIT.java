package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.floci.testcontainers.FlociContainer;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real storage path against a real floci container: DynamoDB
 * in-process inside floci, and a real {@code valkey/valkey:8} container that floci
 * launches through the mounted Docker socket and fronts on its own RESP proxy port.
 *
 * <p>The tool-attribute test is the important one. LangChain4j's tool-search
 * bookkeeping travels in {@code ToolExecutionResultMessage.attributes()}, so a store
 * that loses attributes across a serialization round trip would silently reset
 * progressive tool disclosure on every turn — a correctness bug that shows up only
 * as extra latency and tokens, never as an error.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatMemoryStoreFlociIT {

    private static final int RESP_PROXY_PORT = 6379;

    private FlociContainer floci;
    private ApplicationContext context;
    private ChatMemoryStore store;

    @BeforeAll
    void startInfrastructure() {
        floci = new FlociContainer("floci/floci:1.6.0");
        floci.start();

        context = ApplicationContext.run(Map.of(
                "agentic.aws.endpoint", floci.getEndpoint(),
                "agentic.aws.region", floci.getRegion(),
                "agentic.aws.access-key-id", floci.getAccessKey(),
                "agentic.aws.secret-access-key", floci.getSecretKey(),
                "agentic.valkey.host", floci.getHost(),
                "agentic.valkey.port", floci.getMappedPort(RESP_PROXY_PORT),
                "agentic.persistence.bootstrap-enabled", true));

        store = context.getBean(WriteThroughChatMemoryStore.class);
    }

    @AfterAll
    void stopInfrastructure() {
        if (context != null) {
            context.close();
        }
        if (floci != null) {
            floci.stop();
        }
    }

    @Test
    void roundTripsAConversationThroughValkeyAndDynamoDb() {
        var id = ConversationId.newId().value();
        List<ChatMessage> messages = List.of(
                UserMessage.from("Qual o CEP da Avenida Paulista?"),
                AiMessage.from("Vou consultar."));

        store.updateMessages(id, messages);

        assertThat(store.getMessages(id)).isEqualTo(messages);
        assertThat(context.getBean(io.github.rodrigorjsf.agenticchat.memory.valkey.ValkeyChatMemoryStore.class)
                .getMessages(id)).isEqualTo(messages);
        assertThat(context.getBean(io.github.rodrigorjsf.agenticchat.memory.dynamodb.DynamoDbChatMemoryStore.class)
                .getMessages(id)).isEqualTo(messages);
    }

    @Test
    void preservesToolExecutionResultAttributesAcrossBothStores() {
        var id = ConversationId.newId().value();
        var request = ToolExecutionRequest.builder()
                .id("call_1")
                .name("tool_search_tool")
                .arguments("{\"terms\":[\"cep\"]}")
                .build();
        var toolResult = ToolExecutionResultMessage.builder()
                .id(request.id())
                .toolName(request.name())
                .text("Tools found: brasilapi_cep")
                .attributes(Map.of("found_tools", List.of("brasilapi_cep")))
                .build();

        store.updateMessages(id, List.of(UserMessage.from("cep"), toolResult));

        var fromValkey = context.getBean(io.github.rodrigorjsf.agenticchat.memory.valkey.ValkeyChatMemoryStore.class)
                .getMessages(id);
        var fromDynamo = context.getBean(io.github.rodrigorjsf.agenticchat.memory.dynamodb.DynamoDbChatMemoryStore.class)
                .getMessages(id);

        assertThat(attributesOf(fromValkey))
                .as("Valkey must not drop ToolExecutionResultMessage attributes")
                .containsEntry("found_tools", List.of("brasilapi_cep"));
        assertThat(attributesOf(fromDynamo))
                .as("DynamoDB must not drop ToolExecutionResultMessage attributes")
                .containsEntry("found_tools", List.of("brasilapi_cep"));
    }

    @Test
    void deleteRemovesTheConversationEverywhere() {
        var id = ConversationId.newId().value();
        store.updateMessages(id, List.of(UserMessage.from("apagar")));

        store.deleteMessages(id);

        assertThat(store.getMessages(id)).isEmpty();
        assertThat(context.getBean(io.github.rodrigorjsf.agenticchat.memory.dynamodb.DynamoDbChatMemoryStore.class)
                .getMessages(id)).isEmpty();
    }

    @Test
    void survivesTheValkeyEntryDisappearing() {
        var id = ConversationId.newId().value();
        List<ChatMessage> messages = List.of(UserMessage.from("durabilidade"));
        store.updateMessages(id, messages);

        context.getBean(io.github.rodrigorjsf.agenticchat.memory.valkey.ValkeyChatMemoryStore.class)
                .deleteMessages(id);

        assertThat(store.getMessages(id))
                .as("a Valkey eviction must not lose the conversation")
                .isEqualTo(messages);
    }

    private static Map<String, Object> attributesOf(List<ChatMessage> messages) {
        return messages.stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ToolExecutionResultMessage was stored"))
                .attributes();
    }
}
