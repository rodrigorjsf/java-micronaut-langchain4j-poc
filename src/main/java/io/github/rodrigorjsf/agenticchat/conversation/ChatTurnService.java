package io.github.rodrigorjsf.agenticchat.conversation;

import dev.langchain4j.guardrail.GuardrailException;
import dev.langchain4j.invocation.InvocationParameters;
import io.github.rodrigorjsf.agenticchat.agent.ChatAssistant;
import io.github.rodrigorjsf.agenticchat.guardrail.output.VoiceComplianceGuardrail;
import io.github.rodrigorjsf.agenticchat.memory.ConversationCompactor;
import io.github.rodrigorjsf.agenticchat.memory.ConversationId;
import io.github.rodrigorjsf.agenticchat.rag.SkillAwareQueryRouter;
import io.github.rodrigorjsf.agenticchat.skills.SkillCatalog;
import io.github.rodrigorjsf.agenticchat.triage.RefusalTemplates;
import io.github.rodrigorjsf.agenticchat.triage.TriageService;
import io.github.rodrigorjsf.agenticchat.triage.TriageVerdict;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One user turn, end to end.
 *
 * <p>This is the only place the pipeline's shape is visible, and it is deliberately
 * flat: triage, then either a refusal or the agent. No framework decides the order;
 * a reader can see the whole request path in one method.
 *
 * <p>The cheap decision comes first. An out-of-scope turn costs one call to a small
 * model — often zero, when a pre-filter or the cache answers — instead of a full
 * agent turn with the system prompt, the skills index, the tool schemas and the
 * conversation history. That gap is the entire economic argument for having a judge.
 */
@Singleton
public class ChatTurnService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatTurnService.class);

    private static final String GUARDRAIL_REFUSAL =
            "Não consigo ajudar com essa mensagem. Posso te ajudar com dados públicos brasileiros, "
                    + "clima e informações gerais — é só perguntar.";

    private final TriageService triage;
    private final ChatAssistant assistant;
    private final SkillCatalog skills;
    private final RefusalTemplates refusals;
    private final ConversationCompactor compactor;
    private final MeterRegistry meters;

    public ChatTurnService(TriageService triage,
                           ChatAssistant assistant,
                           SkillCatalog skills,
                           RefusalTemplates refusals,
                           ConversationCompactor compactor,
                           MeterRegistry meters) {
        this.triage = triage;
        this.assistant = assistant;
        this.skills = skills;
        this.refusals = refusals;
        this.compactor = compactor;
        this.meters = meters;
    }

    public ChatTurn handle(ConversationId conversationId, String message) {
        var sample = Timer.start(meters);

        // The verdict is model output. Its skill hint reaches the agent's prompt, so
        // it is checked against the published catalogue before it gets there — the
        // guardrail chain reads the user's message, not this object.
        TriageVerdict verdict = triage.triage(message).withSkillHintIn(skills.names());
        if (!verdict.inScope()) {
            sample.stop(meters.timer("agentic.turn.latency", "path", "refused"));
            LOG.info("Turn refused as out of scope: conversation={} intent={}",
                    conversationId, verdict.intent());
            return ChatTurn.refused(verdict, refusals.refusalFor(verdict));
        }

        try {
            // The skill hint travels as an invocation parameter, not in the prompt:
            // the query router needs it, and the model does not need to read it twice.
            var parameters = InvocationParameters.from(
                    SkillAwareQueryRouter.SKILL_HINT, verdict.skillHint());
            // The voice document mandates a sentence, word for word, for a reply to an
            // offensive message. Whether the USER was offensive is the one thing the
            // output guardrail cannot read off the answer, so the verdict's flag rides
            // the same carrier as the skill hint — never the prompt, where it would be
            // one more line for a user to argue with.
            parameters.put(VoiceComplianceGuardrail.OFFENCE_KEY, verdict.carriesOffence());
            var result = assistant.chat(
                    conversationId.value(), message, verdict.language(), verdict.skillHint(), parameters);
            sample.stop(meters.timer("agentic.turn.latency", "path", "answered"));
            // At the END of the turn, so the user never waits for it, and only when
            // the conversation is actually over budget.
            compactor.compactIfNeeded(conversationId);
            return ChatTurn.answered(verdict, result);
        } catch (GuardrailException e) {
            // The guardrail already logged what it found. What reaches the user must
            // not say which rule fired, or the message becomes an oracle.
            sample.stop(meters.timer("agentic.turn.latency", "path", "blocked"));
            meters.counter("agentic.turn.guardrail_blocks").increment();
            return ChatTurn.blocked(verdict, GUARDRAIL_REFUSAL);
        }
    }
}
