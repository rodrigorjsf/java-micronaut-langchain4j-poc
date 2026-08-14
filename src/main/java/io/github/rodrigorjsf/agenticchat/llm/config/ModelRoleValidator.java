package io.github.rodrigorjsf.agenticchat.llm.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects model configurations that are accepted by the API but behave badly, so
 * the failure is a startup error instead of a latency bill.
 *
 * <p>The rule that matters: Google moved the thinking control from an integer
 * {@code thinkingBudget} (Gemini 2.x) to an enum {@code thinkingLevel} (Gemini 3.x).
 * Sending {@code thinkingBudget: 0} to a Gemini 3.x model is not rejected — it is
 * ignored, and the model thinks anyway. Measured on this project against
 * {@code gemini-3.1-flash-lite} with an identical 84-token triage prompt:
 *
 * <pre>
 *   gemini-2.5-flash-lite + thinkingBudget 0   ->  median 0.91 s, worst 1.65 s
 *   gemini-3.1-flash-lite + thinkingLevel low  ->  median 3.32 s, worst 6.28 s
 *   gemini-3.1-flash-lite + thinkingBudget 0   ->  median 5.93 s, worst 10.11 s
 * </pre>
 *
 * <p>A silent 6.5x latency regression on the request path is exactly the kind of
 * misconfiguration that survives code review, so it is enforced here.
 * {@code gemini-3.5-flash-lite} does reject {@code thinkingBudget} outright with
 * HTTP 400 {@code INVALID_ARGUMENT}, which is a different failure for the same
 * mistake — another reason to catch it before the first request.
 */
public final class ModelRoleValidator {

    /**
     * Matches the major version in names like {@code gemini-3.1-flash-lite}.
     */
    private static final Pattern GEMINI_MAJOR = Pattern.compile("^gemini-(\\d+)(?:\\.\\d+)?-");

    private ModelRoleValidator() {
    }

    public static void validate(ModelRoleProperties role) {
        if (role.modelName() == null || role.modelName().isBlank()) {
            throw new InvalidModelConfigurationException(
                    "agentic.llm.models.%s.model-name is required".formatted(role.name()));
        }
        if (role.provider() != ModelProvider.GOOGLE) {
            rejectThinkingOn(role);
            return;
        }
        geminiMajorVersion(role.modelName()).ifPresent(major -> {
            if (major >= 3 && role.thinkingBudget() != null) {
                throw new InvalidModelConfigurationException(("""
                        agentic.llm.models.%s uses %s, a Gemini 3.x model, with thinking-budget=%d. \
                        Gemini 3.x ignores thinking-budget and takes thinking-level \
                        (minimal|low|medium|high) instead. Leaving it set does not disable thinking; \
                        it measurably slows the call down (median 5.93 s vs 0.91 s on gemini-2.5-flash-lite \
                        for the same prompt) and some 3.x models reject the request with HTTP 400.\
                        """).formatted(role.name(), role.modelName(), role.thinkingBudget()));
            }
            if (major < 3 && role.thinkingLevel() != null) {
                throw new InvalidModelConfigurationException(("""
                        agentic.llm.models.%s uses %s, a Gemini 2.x model, with thinking-level=%s. \
                        Gemini 2.x takes an integer thinking-budget; thinking-level is silently ignored, \
                        so the model keeps thinking and the setting is a lie.\
                        """).formatted(role.name(), role.modelName(), role.thinkingLevel()));
            }
        });
    }

    private static void rejectThinkingOn(ModelRoleProperties role) {
        if (role.thinkingBudget() != null || role.thinkingLevel() != null) {
            throw new InvalidModelConfigurationException(
                    "agentic.llm.models.%s sets a thinking option, which only applies to the GOOGLE provider"
                            .formatted(role.name()));
        }
    }

    private static java.util.Optional<Integer> geminiMajorVersion(String modelName) {
        Matcher matcher = GEMINI_MAJOR.matcher(modelName);
        return matcher.find()
                ? java.util.Optional.of(Integer.parseInt(matcher.group(1)))
                : java.util.Optional.empty();
    }

    /**
     * Thrown at startup, on purpose: a bad model config must never reach production traffic.
     */
    public static class InvalidModelConfigurationException extends RuntimeException {
        public InvalidModelConfigurationException(String message) {
            super(message);
        }
    }
}
