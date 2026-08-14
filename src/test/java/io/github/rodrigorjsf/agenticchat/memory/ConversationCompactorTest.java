package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationCompactorTest {

    private static final int TRIGGER = 500;

    private final InMemoryFakeStore store = new InMemoryFakeStore();
    private final RecordingSummarizer summarizer = new RecordingSummarizer();
    private final ConversationCompactor compactor =
            new ConversationCompactor(store, summarizer, new SimpleMeterRegistry(), TRIGGER);

    private static final class RecordingSummarizer implements ConversationSummarizer {
        List<ChatMessage> lastInput;
        String next = "- fact A, from lookup_cep\n- user wants pt-BR";

        @Override
        public String summarize(List<ChatMessage> messages) {
            lastInput = List.copyOf(messages);
            return next;
        }
    }

    private static ToolExecutionResultMessage activation(String skill) {
        return ToolExecutionResultMessage.builder()
                .id("act-" + skill)
                .toolName("activate_skill")
                .text("Activated " + skill)
                .attributes(Map.of(ConversationCompactor.ACTIVATED_SKILL_ATTRIBUTE, skill))
                .build();
    }

    private static ToolExecutionResultMessage toolResult(String tool, String text) {
        return ToolExecutionResultMessage.builder().id(tool + "-1").toolName(tool).text(text).build();
    }

    private static List<ChatMessage> longConversation() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from("system prompt"));
        messages.add(activation("brazil-civic-data"));
        for (int i = 0; i < 12; i++) {
            messages.add(UserMessage.from("pergunta numero " + i + " ".repeat(60)));
            messages.add(toolResult("lookup_cep", "{\"cep\":\"0131010" + i + "\"}" + "x".repeat(300)));
            messages.add(AiMessage.from("resposta numero " + i + " ".repeat(60)));
        }
        return messages;
    }

    @Test
    @DisplayName("RULE 0: an activation-bearing message is never summarised away")
    void activationsSurviveCompaction() {
        // If this ever fails, the agent silently loses every tool and starts
        // answering from parametric memory. No exception, no log line.
        var compacted = compactor.compact(longConversation());

        assertThat(compacted).anyMatch(ConversationCompactor::carriesActivation);
        assertThat(compacted.stream().filter(ConversationCompactor::carriesActivation).count())
                .isEqualTo(1);
    }

    @Test
    void keepsTheSystemMessageFirst() {
        var compacted = compactor.compact(longConversation());

        assertThat(compacted.getFirst()).isInstanceOf(SystemMessage.class);
    }

    @Test
    @DisplayName("the summary sits at position 1, the primacy slot")
    void theSummaryIsPlacedImmediatelyAfterTheSystemMessage() {
        var compacted = compactor.compact(longConversation());

        assertThat(compacted.get(1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) compacted.get(1)).singleText())
                .contains("<conversation_summary>", "lookup_cep");
    }

    @Test
    void keepsTheLastSixMessagesVerbatim() {
        var original = longConversation();

        var compacted = compactor.compact(original);

        assertThat(compacted.subList(compacted.size() - 6, compacted.size()))
                .isEqualTo(original.subList(original.size() - 6, original.size()));
    }

    @Test
    void shrinksTheConversation() {
        var original = longConversation();

        var compacted = compactor.compact(original);

        assertThat(ConversationCompactor.estimateTokens(compacted))
                .isLessThan(ConversationCompactor.estimateTokens(original));
        assertThat(compacted.size()).isLessThan(original.size());
    }

    @Test
    @DisplayName("failed tool results are dropped entirely — a failure carries no reusable fact")
    void failedToolResultsAreDropped() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from("system"));
        for (String prefix : ConversationCompactor.failurePrefixes()) {
            messages.add(toolResult("some_tool", prefix + " " + "y".repeat(400)));
        }
        messages.add(toolResult("good_tool", "{\"ok\":true}"));
        for (int i = 0; i < 8; i++) {
            messages.add(UserMessage.from("filler " + i + " ".repeat(200)));
        }

        var compacted = compactor.compact(messages);

        assertThat(compacted)
                .as("a failed result carries no reusable fact and should be gone")
                .noneMatch(m -> m instanceof ToolExecutionResultMessage r
                        && ConversationCompactor.failurePrefixes().stream().anyMatch(r.text()::startsWith));
        assertThat(compacted)
                .as("the successful one survives")
                .anyMatch(m -> m instanceof ToolExecutionResultMessage r && r.text().contains("\"ok\""));
    }

    @Test
    void identicalToolResultsAreDeduplicated() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from("system"));
        for (int i = 0; i < 6; i++) {
            messages.add(toolResult("lookup_cep", "{\"cep\":\"01310100\"}"));
        }
        for (int i = 0; i < 8; i++) {
            messages.add(UserMessage.from("filler " + i + " ".repeat(200)));
        }

        // The cheap pass may well get under the trigger on its own — which is the
        // point of trying it first — so assert on the result, not on whether the
        // summariser was reached.
        var compacted = compactor.compact(messages);

        assertThat(compacted)
                .filteredOn(m -> m instanceof ToolExecutionResultMessage r
                        && "lookup_cep".equals(r.toolName()))
                .as("six identical results collapse to one")
                .hasSize(1);
    }

    @Test
    @DisplayName("when the summariser is unavailable the messages are kept, not lost")
    void anUnavailableSummarizerLosesNothing() {
        summarizer.next = "";

        var compacted = compactor.compact(longConversation());

        assertThat(compacted)
                .as("no summary, so the reduced messages must still be there")
                .anyMatch(UserMessage.class::isInstance);
        assertThat(compacted).noneMatch(m -> m instanceof UserMessage u
                && u.hasSingleText() && u.singleText().contains("<conversation_summary>"));
    }

    @Test
    void aShortConversationIsNotTouched() {
        var id = ConversationId.newId();
        List<ChatMessage> messages = List.of(
                SystemMessage.from("system"), UserMessage.from("oi"), AiMessage.from("ola"));
        store.updateMessages(id.value(), messages);

        compactor.compactIfNeeded(id);

        assertThat(store.getMessages(id.value())).isEqualTo(messages);
    }

    @Test
    void aLongConversationIsCompactedAndPersisted() {
        var id = ConversationId.newId();
        var original = longConversation();
        store.updateMessages(id.value(), original);

        compactor.compactIfNeeded(id);

        assertThat(store.getMessages(id.value())).hasSizeLessThan(original.size());
    }

    @Test
    @DisplayName("a failure during compaction leaves the conversation exactly as it was")
    void compactionFailureIsNotFatal() {
        var id = ConversationId.newId();
        var original = longConversation();
        store.updateMessages(id.value(), original);
        store.failWrites = true;

        compactor.compactIfNeeded(id);

        assertThat(store.getMessages(id.value())).isEqualTo(original);
    }

    @Test
    @DisplayName("the activation attribute key matches LangChain4j's, which is not ours to change")
    void theActivationAttributeKeyIsPinned() {
        // dev.langchain4j.skills.ActivateSkillToolExecutor:
        //   static final String ACTIVATED_SKILL_ATTRIBUTE = "activated_skill";
        //   // do not change, will break backward compatibility!
        assertThat(ConversationCompactor.ACTIVATED_SKILL_ATTRIBUTE).isEqualTo("activated_skill");
    }
}
