package io.github.rodrigorjsf.agenticchat.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shrinks a conversation before it starts costing accuracy.
 *
 * <p>The research this exists for, in three numbers: recall degrades with input
 * length across every model tested, even on trivial tasks
 * (<a href="https://research.trychroma.com/context-rot">Chroma</a>); relevant
 * information in the middle of a long prompt is answered 10–20% worse than the
 * same information at the edges (Liu et al., TACL 2024, arXiv:2307.03172); and
 * reasoning accuracy fell from 0.92 to 0.68 at 3 000 tokens of input (Levy et al.,
 * ACL 2024, arXiv:2402.14848). A larger context window does not fix any of that —
 * it is more RAM on a machine with a memory leak.
 *
 * <h2>Rule 0 is an invariant, not a threshold</h2>
 *
 * <b>Messages carrying the skill-activation attribute are never summarised away.</b>
 * LangChain4j reconstructs the visible tool set on every round trip by scanning
 * chat memory for them. Compacting one away silently strips every tool from the
 * agent — no exception, no log line; it just starts answering from parametric
 * memory. Everything else here is negotiable; this is not.
 *
 * <h2>The pass</h2>
 *
 * <ol>
 *   <li><b>Partition.</b> Keep verbatim: the system message, every
 *       activation-bearing message, and the last few messages as a recency anchor.
 *       Everything else is a candidate.</li>
 *   <li><b>Cheap pass, no model call.</b> Drop failed tool results — a failure
 *       carries no reusable fact. Truncate surviving tool results. De-duplicate
 *       identical ones. If this alone gets under the trigger, stop here.</li>
 *   <li><b>Model pass,</b> only if still over: summarise the candidates with the
 *       <em>judge</em> model, not the agent model. This is a compression task, not
 *       a reasoning one, and the judge model is several times faster.</li>
 *   <li><b>Reassemble</b> with the summary at position 1 — the primacy slot,
 *       immediately after the system message.</li>
 * </ol>
 *
 * <p>Compaction runs at the <em>end</em> of a turn, so the user never waits for it.
 */
@Singleton
public class ConversationCompactor {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationCompactor.class);

    /**
     * LangChain4j's own attribute key for skill activation. Copied rather than
     * imported because the constant is package-private in the skills module — and
     * pinned by a test, so a rename upstream fails here instead of silently.
     */
    static final String ACTIVATED_SKILL_ATTRIBUTE = "activated_skill";

    /**
     * Kept verbatim at the end. Enough to preserve the immediate exchange.
     */
    private static final int RECENCY_ANCHOR = 6;

    /**
     * Characters kept from a surviving tool result in the cheap pass.
     */
    private static final int TOOL_RESULT_CHARS = 1_600;

    private final ChatMemoryStore store;
    private final ConversationSummarizer summarizer;
    private final MeterRegistry meters;
    private final io.micrometer.core.instrument.DistributionSummary conversationTokens;
    private final int triggerTokens;

    public ConversationCompactor(ChatMemoryStore store,
                                 ConversationSummarizer summarizer,
                                 MeterRegistry meters,
                                 @Value("${agentic.agent.compaction-trigger-tokens:14400}") int triggerTokens) {
        this.store = store;
        this.summarizer = summarizer;
        this.meters = meters;
        this.conversationTokens = io.micrometer.core.instrument.DistributionSummary
                .builder("agentic.memory.tokens")
                .description("Estimated tokens in a conversation when compaction was considered")
                .register(meters);
        this.triggerTokens = triggerTokens;
    }

    /**
     * Compacts the conversation if it is over budget. Never throws: a failure here
     * must not fail a turn that already succeeded.
     */
    public void compactIfNeeded(ConversationId conversationId) {
        try {
            var messages = store.getMessages(conversationId.value());
            int before = estimateTokens(messages);
            // A DistributionSummary, not a gauge. Micrometer holds a gauge's source by
            // WEAK reference and registers a given name once, so `gauge(name, boxedInt)`
            // reports the first conversation this process ever compacted and turns NaN
            // after the first GC. A summary records every observation.
            conversationTokens.record(before);
            if (before < triggerTokens) {
                return;
            }

            var compacted = compact(messages);
            int after = estimateTokens(compacted);
            store.updateMessages(conversationId.value(), compacted);

            meters.counter("agentic.memory.compactions").increment();
            LOG.info("Compacted conversation {}: {} -> {} messages, ~{} -> ~{} tokens",
                    conversationId, messages.size(), compacted.size(), before, after);
        } catch (RuntimeException e) {
            meters.counter("agentic.memory.compaction_failures").increment();
            LOG.warn("Compaction failed for conversation {}; the conversation is unchanged",
                    conversationId, e);
        }
    }

    List<ChatMessage> compact(List<ChatMessage> messages) {
        var systemMessage = messages.stream()
                .filter(SystemMessage.class::isInstance)
                .findFirst();

        int anchorFrom = Math.max(0, messages.size() - RECENCY_ANCHOR);
        var anchor = new ArrayList<>(messages.subList(anchorFrom, messages.size()));

        // An activation is a tool RESULT, and a result without the AiMessage that
        // requested it is an orphan: the id in its tool_call has no counterpart, and
        // a provider rejects the list. So the request travels with it — which means
        // finding the request first, before either is classified.
        var activationRequests = requestsBehindActivations(messages, anchorFrom);

        var activations = new ArrayList<ChatMessage>();
        var candidates = new ArrayList<ChatMessage>();
        for (int i = 0; i < anchorFrom; i++) {
            ChatMessage message = messages.get(i);
            if (message instanceof SystemMessage) {
                continue;
            }
            if (carriesActivation(message) || activationRequests.contains(i)) {
                activations.add(message);
            } else {
                candidates.add(message);
            }
        }

        var reduced = cheapPass(candidates);
        var kept = new ArrayList<ChatMessage>();
        systemMessage.ifPresent(kept::add);

        int projected = estimateTokens(kept) + estimateTokens(activations)
                + estimateTokens(anchor) + estimateTokens(reduced);

        if (projected >= triggerTokens && !reduced.isEmpty()) {
            String summary = summarizer.summarize(reduced);
            if (summary != null && !summary.isBlank()) {
                // Position 1: immediately after the system message, the primacy slot
                // that lost-in-the-middle says is read best.
                kept.add(UserMessage.from("<conversation_summary>\n" + summary + "\n</conversation_summary>"));
                meters.counter("agentic.memory.summaries").increment();
            } else {
                kept.addAll(reduced);
            }
        } else {
            kept.addAll(reduced);
        }

        kept.addAll(activations);
        kept.addAll(anchor);
        return kept;
    }

    /**
     * Everything achievable without a model call.
     *
     * <p>Failed tool results go first and go entirely: a failure carries no reusable
     * fact, and keeping three of them teaches the model that this tool fails.
     */
    private List<ChatMessage> cheapPass(List<ChatMessage> candidates) {
        var seen = new HashSet<String>();
        var out = new ArrayList<ChatMessage>(candidates.size());

        for (ChatMessage message : candidates) {
            if (message instanceof ToolExecutionResultMessage result) {
                String text = result.text() == null ? "" : result.text();
                if (looksLikeFailure(text)) {
                    out.add(replaceText(result, DROPPED_FAILURE));
                    continue;
                }
                String fingerprint = result.toolName() + "|" + text;
                if (!seen.add(fingerprint)) {
                    out.add(replaceText(result, DROPPED_DUPLICATE));
                    continue;
                }
                out.add(truncate(result, text));
                continue;
            }
            out.add(message);
        }
        return out;
    }

    /**
     * Matches the outcome prefixes {@code ToolResponse.toModelText()} produces, so a
     * change there is caught by this class's tests rather than quietly disabling the
     * rule.
     */
    private static boolean looksLikeFailure(String text) {
        return text.startsWith("No result:")
                || text.startsWith("Invalid arguments:")
                || text.startsWith("Rate limited:")
                || text.startsWith("The service is unavailable:");
    }

    private static ChatMessage truncate(ToolExecutionResultMessage result, String text) {
        if (text.length() <= TOOL_RESULT_CHARS) {
            return result;
        }
        return ToolExecutionResultMessage.builder()
                .id(result.id())
                .toolName(result.toolName())
                .text(text.substring(0, TOOL_RESULT_CHARS)
                        + "\n\n[truncated during compaction: this result is older context]")
                .attributes(result.attributes())
                .build();
    }

    /**
     * The text a dropped tool result leaves behind.
     *
     * <p>The message stays because removing it breaks the pair; the payload goes
     * because that was the point. Saying which of the two happened keeps the model
     * from reading an empty result as "the tool returned nothing".
     */
    static final String DROPPED_FAILURE =
            "[this call failed earlier in the conversation; the error was dropped during compaction]";

    static final String DROPPED_DUPLICATE =
            "[an identical result appears elsewhere in this conversation; dropped during compaction]";

    /**
     * Indices of the {@link AiMessage}s that requested a skill activation.
     *
     * <p>Matched by tool-call id, and falling back to the nearest preceding
     * {@code AiMessage} when the id is absent — a store that round-trips through a
     * format without ids would otherwise silently orphan every activation.
     */
    private static Set<Integer> requestsBehindActivations(List<ChatMessage> messages, int until) {
        var activationIds = new HashSet<String>();
        for (int i = 0; i < until; i++) {
            if (carriesActivation(messages.get(i))
                    && messages.get(i) instanceof ToolExecutionResultMessage result
                    && result.id() != null) {
                activationIds.add(result.id());
            }
        }
        var requests = new HashSet<Integer>();
        for (int i = 0; i < until; i++) {
            if (!(messages.get(i) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) {
                continue;
            }
            boolean requestsAnActivation = ai.toolExecutionRequests().stream()
                    .anyMatch(request -> activationIds.contains(request.id()));
            if (requestsAnActivation) {
                requests.add(i);
                continue;
            }
            // No id to match on: keep the request if the very next message is an
            // activation, which is the shape every real exchange has.
            if (i + 1 < until && carriesActivation(messages.get(i + 1))
                    && messages.get(i + 1) instanceof ToolExecutionResultMessage next
                    && next.id() == null) {
                requests.add(i);
            }
        }
        return requests;
    }

    private static ChatMessage replaceText(ToolExecutionResultMessage result, String text) {
        return ToolExecutionResultMessage.builder()
                .id(result.id())
                .toolName(result.toolName())
                .text(text)
                .attributes(result.attributes())
                .build();
    }

    static boolean carriesActivation(ChatMessage message) {
        return message instanceof ToolExecutionResultMessage result
                && result.attributes() != null
                && result.attributes().containsKey(ACTIVATED_SKILL_ATTRIBUTE);
    }

    /**
     * Four characters per token.
     *
     * <p>An approximation on purpose. This number chooses when to compact; it never
     * appears on an invoice. A real tokenizer would add a dependency and a per-turn
     * cost to make a threshold decision more precise than the threshold itself is.
     */
    static int estimateTokens(List<ChatMessage> messages) {
        int characters = 0;
        for (ChatMessage message : messages) {
            characters += textOf(message).length();
        }
        return characters / 4;
    }

    private static String textOf(ChatMessage message) {
        return switch (message) {
            case SystemMessage system -> system.text();
            case UserMessage user -> user.hasSingleText() ? user.singleText() : user.toString();
            case AiMessage ai -> ai.text() == null ? ai.toString() : ai.text();
            case ToolExecutionResultMessage result -> result.text() == null ? "" : result.text();
            default -> message.toString();
        };
    }

    /**
     * Exposed for the test that pins the recency anchor.
     */
    static Set<String> failurePrefixes() {
        return Set.of("No result:", "Invalid arguments:", "Rate limited:", "The service is unavailable:");
    }
}
