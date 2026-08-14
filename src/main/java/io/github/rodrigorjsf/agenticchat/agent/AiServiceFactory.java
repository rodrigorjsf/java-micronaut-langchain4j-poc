package io.github.rodrigorjsf.agenticchat.agent;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.github.rodrigorjsf.agenticchat.guardrail.input.InjectionTriageGuardrail;
import io.github.rodrigorjsf.agenticchat.guardrail.input.NormalizingInputGuardrail;
import io.github.rodrigorjsf.agenticchat.guardrail.output.ExfiltrationGuardrail;
import io.github.rodrigorjsf.agenticchat.guardrail.output.SystemPromptLeakageGuardrail;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.memory.SummarizerPrompt;
import io.github.rodrigorjsf.agenticchat.skills.SkillCatalog;
import io.github.rodrigorjsf.agenticchat.triage.TriageJudge;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Wires the two AI services. Every non-default choice below is a decision that
 * would otherwise be invisible.
 */
@Factory
public class AiServiceFactory {

    private static final Logger LOG = LoggerFactory.getLogger(AiServiceFactory.class);

    /**
     * The classifier gets nothing beyond a model. No memory — its prompt would grow
     * without bound on the component that runs before every request. No tools — a
     * classifier that can act is an escalation path. No guardrails — the injection
     * guardrail would call this same class and recurse.
     */
    @Singleton
    TriageJudge triageJudge(ChatModelRegistry models) {
        return AiServices.builder(TriageJudge.class)
                .chatModel(models.forRole("judge"))
                .build();
    }

    /**
     * Compaction runs on the judge model, not the agent model. Compressing an old
     * transcript is not reasoning, and the judge model measured a 0.91 s median
     * against 5.93 s on this machine.
     */
    @Singleton
    SummarizerPrompt summarizerPrompt(ChatModelRegistry models) {
        return AiServices.builder(SummarizerPrompt.class)
                .chatModel(models.forRole("judge"))
                .build();
    }

    @Singleton
    ChatAssistant chatAssistant(ChatModelRegistry models,
                               SystemPromptBuilder systemPrompt,
                               SkillCatalog skills,
                               ChatMemoryStore memoryStore,
                               RetrievalAugmentor retrievalAugmentor,
                               NormalizingInputGuardrail normalizer,
                               InjectionTriageGuardrail injectionTriage,
                               SystemPromptLeakageGuardrail leakage,
                               ExfiltrationGuardrail exfiltration,
                               @Value("${agentic.agent.memory-window-messages:20}") int memoryWindow,
                               @Value("${agentic.agent.max-tool-round-trips:6}") int maxRoundTrips) {

        LOG.info("Assembling the chat assistant: memory window {} messages, max {} tool round trips",
                memoryWindow, maxRoundTrips);

        return AiServices.builder(ChatAssistant.class)
                .chatModel(models.forRole("agent"))

                // A message window rather than a token window: the cost of a slightly
                // larger prompt is predictable, whereas a token window silently drops a
                // different number of turns for every conversation, which makes
                // behaviour irreproducible between a test and production.
                .chatMemoryProvider(conversationId -> MessageWindowChatMemory.builder()
                        .id(conversationId)
                        .maxMessages(memoryWindow)
                        // The primacy anchor for lost-in-the-middle, set explicitly
                        // rather than relying on the eviction loop's default.
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(memoryStore)
                        .build())

                .systemMessageProvider(conversationId -> systemPrompt.prompt())

                // Retrieval over the assistant's own documentation, routed so it only
                // runs when a tool is not going to answer instead.
                .retrievalAugmentor(retrievalAugmentor)

                // Default is true, which writes every retrieved segment into persisted
                // chat memory: it inflates each DynamoDB item and replays the same
                // prose into every later prompt in the conversation. Retrieval is cheap
                // enough to redo per turn; storing it is not.
                .storeRetrievedContentInChatMemory(false)

                // Skill-scoped tools: only activate_skill and read_skill_resource are
                // visible until the model activates a skill.
                .toolProvider(skills.skills().toolProvider())

                // Order matters and is the annotation order: normalize first so every
                // later check and the model itself see one canonical form.
                .inputGuardrails(List.of(normalizer, injectionTriage))
                .outputGuardrails(List.of(leakage, exfiltration))

                // Without this the default is to throw, which turns a model typo into a
                // 500. Returning the text lets the model correct its own call.
                .hallucinatedToolNameStrategy(request ->
                        dev.langchain4j.data.message.ToolExecutionResultMessage.from(request,
                                "There is no tool called '" + request.name()
                                        + "'. Activate the right skill first, then use the tools it lists."))

                // LangChain4j's default feeds Throwable.getMessage() to the model, which
                // is a documented exfiltration path: stack traces, upstream URLs, response
                // bodies and credentials end up in the prompt, the chat history and the
                // provider's logs. The real cause is logged instead.
                .toolExecutionErrorHandler((error, context) -> {
                    LOG.error("Tool execution failed", error);
                    return ToolErrorHandlerResult.text(
                            "That tool failed. Tell the user this data source is temporarily unavailable.");
                })
                .toolArgumentsErrorHandler((error, context) ->
                        ToolErrorHandlerResult.text("Invalid arguments: " + error.getMessage()
                                + ". Fix them and call the tool again."))

                // A bound on agency. Six round trips is enough for activate_skill plus a
                // couple of chained lookups; beyond that a model is looping, and each
                // loop costs a full prompt.
                .maxToolCallingRoundTrips(maxRoundTrips)

                .build();
    }
}
