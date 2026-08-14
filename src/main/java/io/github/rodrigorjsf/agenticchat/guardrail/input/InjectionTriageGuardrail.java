package io.github.rodrigorjsf.agenticchat.guardrail.input;

import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Position 2 in the input chain: decides whether a turn is a prompt-injection
 * attempt.
 *
 * <p>The heuristics and the classifier live in one guardrail because LangChain4j
 * gives an input guardrail exactly two outcomes — pass or kill. There is no
 * channel for handing a score to the next guardrail, so a "score here, decide
 * there" split is not expressible.
 *
 * <p>Three bands, and the middle one is the whole point:
 * <ul>
 *   <li><b>clean</b> — pass, no model call. The overwhelming majority of traffic.</li>
 *   <li><b>gray zone</b> — one LLM opinion. This is what buys recall on phrasings
 *       no regex anticipated, while keeping the cost off the common path.</li>
 *   <li><b>blocking</b> — structural certainty, no model call. Cheap and final.</li>
 * </ul>
 *
 * <p>The rejection message is deliberately uninformative. A detector that explains
 * which rule fired is a detector the attacker can iterate against.
 */
@Singleton
public class InjectionTriageGuardrail implements InputGuardrail {

    private static final Logger LOG = LoggerFactory.getLogger(InjectionTriageGuardrail.class);

    /**
     * Below this, an INJECTION verdict is treated as noise and the turn passes.
     */
    private static final double CONFIDENCE_THRESHOLD = 0.80;

    private static final String REJECTION = "I can't help with that request.";

    private final InjectionHeuristics heuristics;
    private final InjectionClassifier classifier;

    public InjectionTriageGuardrail(InjectionHeuristics heuristics, InjectionClassifier classifier) {
        this.heuristics = heuristics;
        this.classifier = classifier;
    }

    @Override
    public InputGuardrailResult validate(InputGuardrailRequest request) {
        String text = textOf(request.userMessage());
        if (text == null || text.isBlank()) {
            return success();
        }

        // The normalizer ahead of this guardrail has already rewritten the text, but
        // the invisible-character count only exists in the raw form, so normalize
        // again defensively rather than assuming a chain order.
        String normalized = TextNormalizer.normalize(text);
        var score = heuristics.score(normalized, text);

        if (score.blocks()) {
            LOG.warn("Blocked a turn deterministically: rules={} length={}", score.ruleIds(), text.length());
            return fatal(REJECTION);
        }
        if (score.isClean()) {
            return success();
        }

        var verdict = classifier.classify(normalized);
        LOG.warn("Gray-zone turn: rules={} verdict={} confidence={} reason={}",
                score.ruleIds(), verdict.label(), verdict.confidence(), verdict.reason());

        if (verdict.isConfidentInjection(CONFIDENCE_THRESHOLD)) {
            return fatal(REJECTION);
        }
        return success();
    }

    private static String textOf(UserMessage userMessage) {
        if (userMessage.hasSingleText()) {
            return userMessage.singleText();
        }
        return userMessage.contents().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
