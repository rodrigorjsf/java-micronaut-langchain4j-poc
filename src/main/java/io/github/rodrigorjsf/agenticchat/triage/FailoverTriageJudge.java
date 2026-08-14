package io.github.rodrigorjsf.agenticchat.triage;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Sends the turn to a second provider when the first one is rate-limited.
 *
 * <p>This exists because of a measurement, not a hunch. Google's free tier returns
 * {@code RESOURCE_EXHAUSTED} on
 * {@code generativelanguage.googleapis.com/generate_content_free_tier_requests} for
 * {@code gemini-2.5-flash-lite} at roughly <b>10–20 requests per minute</b> — it
 * tripped at about 8.5 requests/minute during probing. A judge that runs before
 * every request cannot live behind a limit like that with no second path.
 *
 * <p>Failover fires <em>only</em> on rate limiting. A timeout or a malformed
 * response means the primary is unwell in a way a second call would likely repeat,
 * and doubling the latency of an already-slow turn helps nobody: those cases fall
 * through to the caller, which fails open.
 *
 * <p>The fallback is optional. With no {@code judge-fallback} role configured this
 * is a pass-through, and the rate-limited turn fails open like any other — which is
 * the correct behaviour for a component that is a scope filter and not a security
 * boundary.
 */
public class FailoverTriageJudge implements TriageJudge {

    private static final Logger LOG = LoggerFactory.getLogger(FailoverTriageJudge.class);

    private final TriageJudge primary;
    private final TriageJudge fallback;
    private final MeterRegistry meters;

    public FailoverTriageJudge(TriageJudge primary, @Nullable TriageJudge fallback, MeterRegistry meters) {
        this.primary = primary;
        this.fallback = fallback;
        this.meters = meters;
    }

    @Override
    public TriageVerdict classify(String text, String skills) {
        try {
            return primary.classify(text, skills);
        } catch (RuntimeException e) {
            if (fallback == null || !isRateLimit(e)) {
                throw e;
            }
            meters.counter("agentic.triage.failovers").increment();
            LOG.warn("Primary judge rate-limited; falling over to the secondary provider");
            return fallback.classify(text, skills);
        }
    }

    /**
     * Matched on message text because providers signal this differently and
     * LangChain4j does not normalise it into one exception type: Google answers
     * {@code RESOURCE_EXHAUSTED}, OpenAI a 429 with "rate limit".
     */
    static boolean isRateLimit(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("resource_exhausted")
                    || lower.contains("rate limit")
                    || lower.contains("rate_limit")
                    || lower.contains("quota")
                    || lower.contains("429")) {
                return true;
            }
        }
        return false;
    }
}
