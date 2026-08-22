package io.github.rodrigorjsf.agenticchat.observability;

import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider token counts, converted into the mutually exclusive buckets Langfuse stores.
 *
 * <p>The rule is Langfuse's and it is the whole reason this class exists: "each token
 * must be counted in exactly one key … if buckets overlap, usage and inferred cost will
 * be counted double". Both providers report their cached count INSIDE the input count,
 * so a straight copy bills every cache hit twice.
 *
 * <p>Gemini is the opposite case. {@code outputTokenCount} is mapped from
 * {@code candidatesTokenCount} alone (GeminiStreamingResponseBuilder line 122 in
 * langchain4j 1.18.1), so reasoning tokens — billed at the output rate — are in no
 * bucket at all unless they are given one.
 */
class TokenUsageDetailsTest {

    @Test
    @DisplayName("OpenAI's inclusive cached count is subtracted out of input")
    void openAiCachedTokensBecomeTheirOwnBucket() {
        var usage = OpenAiTokenUsage.builder()
                .inputTokenCount(17_903)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(17_817).build())
                .outputTokenCount(188)
                .totalTokenCount(18_091)
                .build();

        // The worked example from the Langfuse token-and-cost-tracking page.
        assertThat(TokenUsageDetails.of(usage).buckets())
                .containsEntry("input", 86L)
                .containsEntry("input_cached_tokens", 17_817L)
                .containsEntry("output", 188L)
                .containsEntry("total", 18_091L);
    }

    @Test
    @DisplayName("Gemini's cached content count is subtracted the same way")
    void geminiCachedContentBecomesItsOwnBucket() {
        var usage = GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(1_000)
                .cachedContentTokenCount(900)
                .outputTokenCount(50)
                .totalTokenCount(1_050)
                .build();

        assertThat(TokenUsageDetails.of(usage).buckets())
                .containsEntry("input", 100L)
                .containsEntry("input_cached_tokens", 900L)
                .containsEntry("output", 50L);
    }

    @Test
    @DisplayName("Gemini reasoning tokens get a bucket of their own instead of vanishing")
    void geminiThoughtsAreCountedSeparately() {
        var usage = GoogleAiGeminiTokenUsage.builder()
                .inputTokenCount(400)
                .outputTokenCount(120)
                .thoughtsTokenCount(880)
                .totalTokenCount(1_400)
                .build();

        var details = TokenUsageDetails.of(usage);

        assertThat(details.buckets())
                .containsEntry("output", 120L)
                .containsEntry("output_reasoning_tokens", 880L);
        // Not folded into output: the buckets have to stay disjoint.
        assertThat(details.reasoningOutputTokens()).isEqualTo(880L);
    }

    @Test
    @DisplayName("OpenAI reasoning tokens are subtracted out of output, Gemini's are not")
    void openAiReasoningTokensAreInclusive() {
        var usage = OpenAiTokenUsage.builder()
                .inputTokenCount(100)
                .outputTokenCount(500)
                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(400).build())
                .build();

        // completion_tokens already contains the reasoning tokens, so output is what is
        // left after taking them out — the opposite of the Gemini case above.
        assertThat(TokenUsageDetails.of(usage).buckets())
                .containsEntry("output", 100L)
                .containsEntry("output_reasoning_tokens", 400L);
    }

    @Test
    @DisplayName("a provider with no cache reporting yields input and output only")
    void aPlainUsageHasNoCacheBucket() {
        var details = TokenUsageDetails.of(new TokenUsage(120, 30));

        assertThat(details.buckets())
                .containsEntry("input", 120L)
                .containsEntry("output", 30L)
                .doesNotContainKey("input_cached_tokens");
        assertThat(details.cachedInputTokens()).isZero();
    }

    @Test
    @DisplayName("a cached count larger than the input count cannot make input negative")
    void anOverReportingProviderCannotDriveInputBelowZero() {
        var usage = OpenAiTokenUsage.builder()
                .inputTokenCount(100)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(500).build())
                .outputTokenCount(10)
                .build();

        assertThat(TokenUsageDetails.of(usage).buckets()).containsEntry("input", 0L);
    }

    @Test
    @DisplayName("no usage at all is an empty set of buckets, never a set of zeroes")
    void absentUsageWritesNothing() {
        assertThat(TokenUsageDetails.of(null).buckets()).isEmpty();
        assertThat(TokenUsageDetails.of(null).isEmpty()).isTrue();
    }
}
