package io.github.rodrigorjsf.agenticchat.triage;

import io.github.rodrigorjsf.agenticchat.guardrail.input.TextNormalizer;
import io.github.rodrigorjsf.agenticchat.observability.trace.AgentTracer;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationContentPolicy;
import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationType;
import io.github.rodrigorjsf.agenticchat.observability.trace.Observed;
import io.github.rodrigorjsf.agenticchat.observability.trace.Score;
import io.github.rodrigorjsf.agenticchat.observability.trace.ScoreWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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

    /**
     * Above this the request is refused without a model call; the guardrails cap it too.
     */
    private static final int MAX_CHARS = 12_000;

    private final CachedTriageJudge judge;
    private final MeterRegistry meters;

    private final ScoreWriter scores;
    private final AgentTracer tracer;
    private final ObservationContentPolicy content;

    public TriageService(CachedTriageJudge judge,
                         MeterRegistry meters,
                         ScoreWriter scores,
                         AgentTracer tracer,
                         ObservationContentPolicy content) {
        this.scores = scores;
        this.judge = judge;
        this.meters = meters;
        this.tracer = tracer;
        this.content = content;
    }

    @Observed(value = "triage", type = ObservationType.CHAIN, captureResult = true)
    public TriageVerdict triage(String rawText) {
        String normalized = TextNormalizer.normalize(rawText == null ? "" : rawText);

        var deterministic = preFilter(normalized);
        if (deterministic != null) {
            count(deterministic, "prefilter");
            return deterministic;
        }

        var sample = Timer.start(meters);
        try {
            var verdict = judged(normalized);
            sample.stop(meters.timer("agentic.triage.latency", "outcome", "ok"));
            count(verdict, "model");
            score(verdict);
            return verdict;
        } catch (RuntimeException e) {
            sample.stop(meters.timer("agentic.triage.latency", "outcome", "error"));
            meters.counter("agentic.triage.failures").increment();
            // Fail open: the guardrail chain still runs on the escalated turn.
            LOG.warn("Triage judge unavailable, escalating the turn to the main agent", e);
            // The judge is where language detection lives, and it is the component
            // that just failed — but its input is still in hand, so the turn is
            // escalated with a language read from the text rather than assumed.
            return TriageVerdict.deterministic(TriageVerdict.Decision.IN_SCOPE,
                    TriageVerdict.Intent.UNKNOWN, MessageLanguage.detect(normalized));
        }
    }

    /**
     * The judge call, as an observation a reader can open.
     *
     * <p>Before this existed the judge left two scores and nothing else. Scores are the
     * right shape for an aggregate — an average confidence, every turn under 0.7 — and the
     * wrong shape for the question a reader actually opens a trace to ask, which is what
     * this judge saw and what it answered. That was reachable only by inferring it from a
     * number, and on a cached turn there was no model call underneath to infer it from
     * either.
     *
     * <p><b>Opened here and not as an {@code @Observed} on {@link CachedTriageJudge#classify}</b>,
     * which is where it belongs conceptually and where it would not work. Both annotations
     * are {@code @Around} advice, and Micronaut orders advice by interceptor phase:
     * {@code InterceptPhase.CACHE} is -100 and {@code TRACE} is -80, so caching wraps
     * tracing. A cache HIT returns from the outer interceptor and the inner one never
     * runs — the annotation would compile, the miss path would look right, and the cached
     * path, which is the common one and the whole reason this method exists, would produce
     * nothing at all. Opening the span at the call site is order-independent.
     *
     * <p>{@code SPAN}, not {@code GENERATION}, and the cached path is the reason. A
     * remembered verdict involves no model, and a generation carrying no tokens and no
     * model name is a zero-cost call that never happened — Langfuse would render it as
     * one. When the judge does reach the model, {@code LangfuseChatModelListener} nests a
     * real generation inside this span; when the cache answers, this span stands alone,
     * and the absence of a child is what says so.
     */
    private TriageVerdict judged(String normalized) {
        try (var observation = tracer.start("triage-judge", ObservationType.SPAN)) {
            observation.input(content.capture(normalized));
            try {
                var verdict = judge.classify(normalized);
                observation.output(content.capture(verdict));
                return verdict;
            } catch (RuntimeException e) {
                // Recorded on the observation and rethrown: triage() fails open above, and
                // a fail-open that leaves no mark is a judge that appears never to have run.
                observation.failed(e);
                throw e;
            }
        }
    }

    /**
     * The judge's own opinion, written where a human annotation and an offline evaluator
     * write theirs.
     *
     * <p>A score rather than a span attribute because Langfuse aggregates scores across
     * traces and does not aggregate arbitrary attributes: "the average confidence this
     * week" and "every turn the judge was under 0.7 sure about" are one query each on a
     * score and no query at all on an attribute. Putting the judge's verdict in the same
     * column its later corrections land in is the point.
     *
     * <p>Only on the model path. The pre-filter answers without asking the judge, and a
     * confidence recorded there would be a number attributed to a component that never
     * ran.
     */
    private void score(TriageVerdict verdict) {
        // The single-argument overload attaches to the CURRENT observation, which at this
        // point is the triage chain — score() is called after judged() has closed its own
        // observation, so the scores land on the step that owns the decision rather than
        // on the judge call inside it.
        scores.record(Score.numeric("triage_confidence", verdict.confidence())
                .withComment(verdict.intent().name()));
        // Categorical, so it groups rather than averages. Averaging IN_SCOPE and
        // OUT_OF_SCOPE would produce a number with no meaning at all.
        scores.record(Score.categorical("triage_decision", verdict.decision().name()));
    }

    /**
     * <p>Each of these three answers a turn without the judge, and the judge is where
     * language detection lives — so each one has to say what language it is answering
     * in. The tag is not cosmetic, and its load-bearing consumer is the refusal path:
     * {@link RefusalTemplates#refusalFor} picks the template by it, and that text
     * reaches the user with no model in the loop to correct it. In the prompt the same
     * tag is only a hint — {@code SystemPromptBuilder} tells the model the message
     * wins where the two disagree — so a wrong tag is recoverable there and final
     * here. All three used to say {@code pt-BR} without looking, which answered
     * "hello" in Portuguese and declined 12 000 characters of English with the
     * Portuguese template while the English one sat unreachable.
     */
    private TriageVerdict preFilter(String normalized) {
        if (normalized.isBlank()) {
            // No text, so no evidence, so nothing to detect. The default is the whole
            // answer here — deliberately, not for want of looking — because this
            // assistant's audience is Brazilian. It is the one path where a constant
            // is the honest choice.
            return TriageVerdict.deterministic(TriageVerdict.Decision.OUT_OF_SCOPE,
                    TriageVerdict.Intent.EMPTY, MessageLanguage.DEFAULT);
        }
        if (normalized.length() > MAX_CHARS) {
            // Over 12 000 characters: more than enough prose for a word list, and the
            // one refusal a user can act on by rewriting, so it had better be in a
            // language they read.
            return TriageVerdict.deterministic(TriageVerdict.Decision.OUT_OF_SCOPE,
                    TriageVerdict.Intent.TOO_LONG, MessageLanguage.detect(normalized));
        }
        String greeting = MessageLanguage.ofGreeting(normalized);
        if (greeting != null) {
            // A lookup, not detection. "hi" is two characters of evidence for any
            // detector and an exact answer for a closed vocabulary that was already
            // partitioned by language.
            return TriageVerdict.deterministic(TriageVerdict.Decision.IN_SCOPE,
                    TriageVerdict.Intent.GREETING, greeting);
        }
        return null;
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
