package io.github.rodrigorjsf.agenticchat.observability;

import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One model call's token counts, split into buckets that do not overlap.
 *
 * <p>Langfuse's contract: "Langfuse treats every key in usage_details as a separate,
 * non-overlapping bucket: each token must be counted in exactly one key … if buckets
 * overlap, usage and inferred cost will be counted double." The providers do not hand
 * their numbers over in that shape, and they are wrong in opposite directions:
 *
 * <ul>
 *   <li><b>Cached input is inclusive.</b> {@code OpenAiTokenUsage.inputTokensDetails()
 *       .cachedTokens()} and {@code GoogleAiGeminiTokenUsage.cachedContentTokenCount()}
 *       are both already inside the input count, so the cached figure is subtracted out.</li>
 *   <li><b>Gemini reasoning tokens are exclusive and unmapped.</b> langchain4j sets
 *       {@code outputTokenCount} from {@code candidatesTokenCount} alone, so
 *       {@code thoughtsTokenCount} — which Google bills at the output rate — is in no
 *       bucket at all until it is given one here.</li>
 * </ul>
 *
 * <p>The bucket names are the ones Langfuse's own OpenAI-schema mapping produces
 * ({@code prompt_tokens_details.* -> input_*}), so a model priced by Langfuse's built-in
 * definitions and a model priced here agree on what they are naming.
 */
public record TokenUsageDetails(Map<String, Long> buckets) {

    public static final String INPUT = "input";
    public static final String INPUT_CACHED = "input_cached_tokens";
    public static final String OUTPUT = "output";
    public static final String OUTPUT_REASONING = "output_reasoning_tokens";
    public static final String TOTAL = "total";

    private static final TokenUsageDetails EMPTY = new TokenUsageDetails(Map.of());

    public TokenUsageDetails {
        buckets = Map.copyOf(buckets);
    }

    public static TokenUsageDetails of(TokenUsage usage) {
        if (usage == null) {
            return EMPTY;
        }
        long reportedInput = value(usage.inputTokenCount());
        long reportedOutput = value(usage.outputTokenCount());
        long cached = cachedInputOf(usage);
        long reasoning = reasoningOutputOf(usage);
        // A provider that reports more cached tokens than input tokens is wrong, but a
        // negative bucket would be this code's fault. Clamp rather than propagate.
        long freshInput = Math.max(0, reportedInput - cached);
        // OpenAI reports reasoning tokens INSIDE completion_tokens, Gemini reports them
        // beside candidatesTokenCount. Subtracting in both cases would lose Gemini's
        // output; subtracting in neither would double-count OpenAI's reasoning.
        long output = reasoningIsInsideOutput(usage) ? Math.max(0, reportedOutput - reasoning) : reportedOutput;

        var buckets = new LinkedHashMap<String, Long>();
        put(buckets, INPUT, freshInput, reportedInput > 0);
        put(buckets, INPUT_CACHED, cached, cached > 0);
        put(buckets, OUTPUT, output, reportedOutput > 0);
        put(buckets, OUTPUT_REASONING, reasoning, reasoning > 0);
        put(buckets, TOTAL, value(usage.totalTokenCount()), value(usage.totalTokenCount()) > 0);
        return new TokenUsageDetails(buckets);
    }

    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    public long inputTokens() {
        return buckets.getOrDefault(INPUT, 0L);
    }

    public long cachedInputTokens() {
        return buckets.getOrDefault(INPUT_CACHED, 0L);
    }

    public long outputTokens() {
        return buckets.getOrDefault(OUTPUT, 0L);
    }

    public long reasoningOutputTokens() {
        return buckets.getOrDefault(OUTPUT_REASONING, 0L);
    }

    /**
     * Provider-specific because the base {@link TokenUsage} has no notion of a cache
     * hit. Reflection-free: both subclasses are on the compile classpath already.
     */
    private static long cachedInputOf(TokenUsage usage) {
        if (usage instanceof OpenAiTokenUsage openAi) {
            var details = openAi.inputTokensDetails();
            return details == null ? 0 : value(details.cachedTokens());
        }
        if (usage instanceof GoogleAiGeminiTokenUsage gemini) {
            return value(gemini.cachedContentTokenCount());
        }
        return 0;
    }

    private static long reasoningOutputOf(TokenUsage usage) {
        if (usage instanceof GoogleAiGeminiTokenUsage gemini) {
            return value(gemini.thoughtsTokenCount());
        }
        if (usage instanceof OpenAiTokenUsage openAi) {
            var details = openAi.outputTokensDetails();
            return details == null ? 0 : value(details.reasoningTokens());
        }
        return 0;
    }

    /**
     * OpenAI's {@code completion_tokens} includes its reasoning tokens; Gemini's
     * {@code candidatesTokenCount} does not include its thoughts. Same concept, opposite
     * conventions, and getting it backwards is silent in both directions.
     */
    private static boolean reasoningIsInsideOutput(TokenUsage usage) {
        return usage instanceof OpenAiTokenUsage;
    }

    private static void put(Map<String, Long> buckets, String key, long value, boolean present) {
        if (present) {
            buckets.put(key, value);
        }
    }

    private static long value(Integer count) {
        return count == null ? 0 : count;
    }
}
