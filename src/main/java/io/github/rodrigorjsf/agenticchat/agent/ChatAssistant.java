package io.github.rodrigorjsf.agenticchat.agent;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * The conversational agent.
 *
 * <p>No {@code @SystemMessage} annotation: the system prompt is assembled once at
 * startup and supplied through {@code systemMessageProvider}. An annotation would
 * have to interpolate the skills index and the integrity marker as method
 * parameters, which puts process-constant text into a per-call signature for no
 * reason.
 *
 * <p>Per-turn context is wrapped in the <em>user</em> message rather than injected
 * into the system prompt, for two reasons that happen to agree. First, caching: a
 * provider's automatic prompt cache keys on an exact prefix, so anything that
 * changes per turn must sit after everything that does not. Second, isolation: the
 * user's text arrives inside a {@code <message>} element that the system prompt
 * names as untrusted data, which gives the model a structural cue that this region
 * is content rather than instruction.
 *
 * <p>{@code response_style} is one line, not the voice document. The document itself
 * is the last section of the system prompt, where it is process-constant and billed
 * as cached input; repeating it per turn would put ~2100 uncached tokens into every
 * message and then replay them out of chat memory for the rest of the conversation.
 * What the turn needs is not another copy but a reminder close to the point of
 * writing, after the tool results have pushed the prompt's opening a long way back.
 *
 * <p>{@link InvocationParameters} is LangChain4j's own per-invocation carrier. It
 * is how the triage verdict reaches components that run inside the invocation —
 * here, the query router — without smuggling values through the prompt text.
 *
 * <p>Returns {@link Result} rather than {@code String} so the caller can read token
 * usage, finish reason, retrieved sources and the tool executions that happened —
 * all of which the cost accounting and the trace need, and none of which survive
 * a bare String return.
 */
public interface ChatAssistant {

    @UserMessage("""
            <turn_context>
            reply_language: {{language}}
            suggested_skill: {{skillHint}}
            response_style: siga <tom_e_voz_de_comunicacao> integralmente nesta resposta
            </turn_context>
            <message>
            {{message}}
            </message>""")
    Result<String> chat(
            @MemoryId String conversationId,
            @V("message") String message,
            @V("language") String language,
            @V("skillHint") String skillHint,
            InvocationParameters parameters);
}
