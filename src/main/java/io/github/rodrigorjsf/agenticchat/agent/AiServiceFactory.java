package io.github.rodrigorjsf.agenticchat.agent;

import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.github.rodrigorjsf.agenticchat.guardrail.input.InjectionHeuristics;
import io.github.rodrigorjsf.agenticchat.guardrail.input.InjectionTriageGuardrail;
import io.github.rodrigorjsf.agenticchat.guardrail.input.NormalizingInputGuardrail;
import io.github.rodrigorjsf.agenticchat.guardrail.output.ExfiltrationGuardrail;
import io.github.rodrigorjsf.agenticchat.guardrail.output.SystemPromptLeakageGuardrail;
import io.github.rodrigorjsf.agenticchat.guardrail.output.VoiceComplianceGuardrail;
import io.github.rodrigorjsf.agenticchat.guardrail.tool.ToolGuardProvider;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.github.rodrigorjsf.agenticchat.memory.SummarizerPrompt;
import io.github.rodrigorjsf.agenticchat.skills.SkillCatalog;
import io.github.rodrigorjsf.agenticchat.triage.FailoverTriageJudge;
import io.github.rodrigorjsf.agenticchat.triage.TriageJudge;
import io.micrometer.core.instrument.MeterRegistry;
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
    TriageJudge triageJudge(ChatModelRegistry models, MeterRegistry meters) {
        var primary = AiServices.builder(TriageJudge.class)
                .chatModel(models.forRole("judge"))
                .build();

        // Google's free tier returns RESOURCE_EXHAUSTED at roughly 10-20 requests per
        // minute, and this component runs before every request. A second provider is
        // not redundancy, it is the difference between a rate limit degrading the
        // service and stopping it. Optional: with no judge-fallback role configured
        // the rate-limited turn simply fails open.
        TriageJudge fallback = null;
        if (models.roles().contains("judge-fallback")) {
            fallback = AiServices.builder(TriageJudge.class)
                    .chatModel(models.forRole("judge-fallback"))
                    .build();
            LOG.info("Triage judge has a fallback provider configured");
        } else {
            LOG.warn("No judge-fallback role configured; a rate-limited judge will fail open");
        }
        return new FailoverTriageJudge(primary, fallback, meters);
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
                                VoiceComplianceGuardrail voice,
                                InjectionHeuristics heuristics,
                                MeterRegistry meters,
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
                // visible until the model activates a skill. Wrapped so every tool
                // RESULT is screened — guardrails run before and after the tool loop
                // and can never see what a tool returned, which is exactly where
                // indirect prompt injection arrives.
                .toolProvider(new ToolGuardProvider(
                        skills.skills().toolProvider(), heuristics, meters))

                // Order matters and is the annotation order: normalize first so every
                // later check and the model itself see one canonical form.
                .inputGuardrails(List.of(normalizer, injectionTriage))

                // Order is severity, descending, and it is load-bearing. The first two
                // withhold the answer and delete it from memory; the third repairs the
                // answer and delivers it. Running the voice check first would spend a
                // reprompt polishing the emoji on a response that is about to be
                // withheld for leaking the prompt.
                .outputGuardrails(List.of(leakage, exfiltration, voice))

                // Without this the default is to throw, which turns a model typo into a
                // 500. Returning the text lets the model correct its own call.
                .hallucinatedToolNameStrategy(request ->
                        ToolExecutionResultMessage.from(request,
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
                // Same rule, and it was broken here: getMessage() on an argument-binding
                // failure carries the argument values, which is where a credential a
                // model was tricked into passing would appear.
                .toolArgumentsErrorHandler((error, context) -> {
                    LOG.warn("Tool arguments could not be bound", error);
                    return ToolErrorHandlerResult.text(
                            "Those arguments did not match the tool's parameters. Re-read the "
                                    + "parameter descriptions and call it again, or ask the user "
                                    + "for the detail that is missing.");
                })

                // A bound on agency. Six round trips is enough for activate_skill plus a
                // couple of chained lookups; beyond that a model is looping, and each
                // loop costs a full prompt.
                .maxToolCallingRoundTrips(maxRoundTrips)

                .build();
    }
}
