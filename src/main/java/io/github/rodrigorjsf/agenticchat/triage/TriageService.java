package io.github.rodrigorjsf.agenticchat.triage;

import io.github.rodrigorjsf.agenticchat.guardrail.input.TextNormalizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

/**
 * Everything that happens to a user turn before the main agent sees it.
 *
 * <p>The judge is on the hot path of every request, so the design goal is to call
 * it as rarely as possible without weakening the decision:
 *
 * <ol>
 *   <li><b>Deterministic pre-filters</b> answer the cases where a model adds
 *       nothing. An empty message and a bare "oi" do not need an LLM opinion, and
 *       greetings are a large share of real chat traffic.</li>
 *   <li><b>An exact-match cache</b> on the normalized text. The judge is stateless
 *       and runs at temperature 0, so the same text always yields the same verdict —
 *       which makes caching correct rather than merely convenient. Repeated and
 *       adversarial traffic hits it hardest, which is exactly when the saving
 *       matters.</li>
 *   <li><b>The model</b>, for everything left.</li>
 * </ol>
 *
 * <p><b>Fail-open, deliberately.</b> If the judge times out or errors, the turn is
 * passed to the main agent rather than refused. The judge is a scope filter, not a
 * security control: injection defence lives in the guardrail chain and tool access
 * lives behind the skill catalogue, and neither depends on this class. Failing
 * closed here would convert a Gemini rate limit into a total outage while
 * protecting nothing — the guardrails still run on the escalated turn.
 */
@Singleton
public class TriageService {

    private static final Logger LOG = LoggerFactory.getLogger(TriageService.class);

    /** Above this the request is refused without a model call; the guardrails cap it too. */
    private static final int MAX_CHARS = 12_000;

    /**
     * Whole-message greetings only. A message that merely starts with "oi" carries a
     * real request after it and must reach the judge.
     */
    private static final Set<String> GREETINGS = Set.of(
            "oi", "ola", "opa", "eai", "e ai", "bom dia", "boa tarde", "boa noite",
            "hi", "hello", "hey", "yo", "hola",
            "obrigado", "obrigada", "valeu", "vlw", "thanks", "thank you", "tchau", "ate mais", "bye");

    private final CachedTriageJudge judge;
    private final MeterRegistry meters;

    public TriageService(CachedTriageJudge judge, MeterRegistry meters) {
        this.judge = judge;
        this.meters = meters;
    }

    public TriageVerdict triage(String rawText) {
        String normalized = TextNormalizer.normalize(rawText == null ? "" : rawText);

        var deterministic = preFilter(normalized);
        if (deterministic != null) {
            count(deterministic, "prefilter");
            return deterministic;
        }

        var sample = Timer.start(meters);
        try {
            var verdict = judge.classify(normalized);
            sample.stop(meters.timer("agentic.triage.latency", "outcome", "ok"));
            count(verdict, "model");
            return verdict;
        } catch (RuntimeException e) {
            sample.stop(meters.timer("agentic.triage.latency", "outcome", "error"));
            meters.counter("agentic.triage.failures").increment();
            // Fail open: the guardrail chain still runs on the escalated turn.
            LOG.warn("Triage judge unavailable, escalating the turn to the main agent", e);
            return TriageVerdict.deterministic(
                    TriageVerdict.Decision.IN_SCOPE, TriageVerdict.Intent.UNKNOWN, "pt-BR");
        }
    }

    private TriageVerdict preFilter(String normalized) {
        if (normalized.isBlank()) {
            return TriageVerdict.deterministic(
                    TriageVerdict.Decision.OUT_OF_SCOPE, TriageVerdict.Intent.EMPTY, "pt-BR");
        }
        if (normalized.length() > MAX_CHARS) {
            return TriageVerdict.deterministic(
                    TriageVerdict.Decision.OUT_OF_SCOPE, TriageVerdict.Intent.TOO_LONG, "pt-BR");
        }
        if (isGreeting(normalized)) {
            return TriageVerdict.deterministic(
                    TriageVerdict.Decision.IN_SCOPE, TriageVerdict.Intent.GREETING, "pt-BR");
        }
        return null;
    }

    private static boolean isGreeting(String normalized) {
        String stripped = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[!?.,;:\\s]+$", "")
                .strip();
        if (stripped.length() > 20) {
            return false;
        }
        String folded = java.text.Normalizer.normalize(stripped, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return GREETINGS.contains(folded);
    }

    private void count(TriageVerdict verdict, String source) {
        meters.counter("agentic.triage.decisions",
                "decision", verdict.decision().name(),
                "intent", verdict.intent().name(),
                "source", source).increment();
        if (verdict.wasUpgradedToInScope()) {
            // The judge wanted to refuse and was not confident enough to be allowed
            // to. Worth its own counter: a rise here means the scope prompt no longer
            // matches real traffic.
            meters.counter("agentic.triage.upgraded_to_in_scope",
                    "intent", verdict.intent().name()).increment();
        }
        verdict.riskFlags().forEach(flag ->
                meters.counter("agentic.triage.risk_flags", "flag", flag).increment());
    }
}
