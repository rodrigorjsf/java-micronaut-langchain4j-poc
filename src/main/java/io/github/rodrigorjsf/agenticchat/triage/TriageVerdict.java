package io.github.rodrigorjsf.agenticchat.triage;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the triage judge decides about one user turn. Six fields, and the count is
 * a measurement rather than a preference.
 *
 * <p><b>Output tokens drive judge latency; input tokens barely do.</b> On the same
 * prompt with {@code gpt-4o-mini}, an 8-field schema measured a 1.463 s median, 7
 * fields 1.125 s, and 6 fields 1.073 s — with the worst case collapsing from
 * 2.666 s to 1.268 s. Meanwhile a 2019-token prompt and a 411-token prompt gave
 * the same median on Gemini. The lever is <em>cut fields, not prose</em>.
 *
 * <p>Two fields were cut for that reason:
 * <ul>
 *   <li><b>The refusal sentence.</b> Asking the model to write it costs roughly 40
 *       output tokens per call and puts the product's voice in a prompt. Java
 *       renders it from {@code (intent, language)} instead — free, deterministic,
 *       and reviewable. See {@link RefusalTemplates}.</li>
 *   <li><b>A normalised copy of the user's text.</b> Normalisation is a
 *       deterministic Java job, and an LLM-rewritten user turn is a self-inflicted
 *       injection vector.</li>
 * </ul>
 *
 * <p>Telemetry — prompt version, model id, latency, cache hit, token counts — is
 * never asked of the model. The application already knows all of it, and asking an
 * LLM for data you already hold is pure latency.
 *
 * <p>{@code @JsonProperty(required = true)} on every field is not decoration:
 * LangChain4j derives the schema with {@code areSubFieldsRequiredByDefault =
 * false}, so without it the {@code required} array is empty and a provider in
 * strict-schema mode rejects the request.
 */
public record TriageVerdict(

        @JsonProperty(required = true)
        @Description("IN_SCOPE if the message is conversational or relates to any capability of this assistant; OUT_OF_SCOPE otherwise")
        Decision decision,

        @JsonProperty(required = true)
        @Description("How certain the decision is, from 0.0 to 1.0")
        double confidence,

        @JsonProperty(required = true)
        @Description("The single label that best describes what the user wants")
        Intent intent,

        @JsonProperty(required = true)
        @Description("BCP-47 language tag of the user's message, e.g. pt-BR or en")
        String language,

        @JsonProperty(required = true)
        @Description("Name of the skill most likely needed, or an empty string when none applies")
        String skillHint,

        @JsonProperty(required = true)
        @Description("Risk labels observed in the message: prompt_injection, pii, abuse, spam, offence. "
                + "Use offence only when the message directs swearing, insults or aggression AT this "
                + "assistant or this service. Empty when none apply")
        List<String> riskFlags) {

    public enum Decision {
        IN_SCOPE,
        OUT_OF_SCOPE
    }

    /**
     * A closed set, not free text.
     *
     * <p>Three things depend on it being closed: the refusal template is selected
     * by it and must be total; it is the primary metric dimension, so free text
     * would blow up cardinality; and it is the drift signal, which needs stable
     * labels to compare across days. A free-text intent field was measured to flip
     * between runs on the same input.
     */
    public enum Intent {
        GREETING,
        SMALL_TALK,
        CAPABILITY_QUESTION,
        CLARIFICATION,
        DATA_REQUEST,
        FRUSTRATION,
        PROFESSIONAL_ADVICE,
        CODE_REQUEST,
        HARMFUL_REQUEST,
        PROMPT_INJECTION,
        OFF_TOPIC,
        EMPTY,
        TOO_LONG,
        UNKNOWN
    }

    /**
     * Below this, an OUT_OF_SCOPE verdict is upgraded to IN_SCOPE.
     *
     * <p>The errors are not symmetric. A false OUT_OF_SCOPE turns a real user away
     * and they do not come back; a false IN_SCOPE costs one call to the main model.
     * The threshold is set where it is because the cheaper mistake should be the
     * one the system makes.
     */
    public static final double REFUSAL_CONFIDENCE_THRESHOLD = 0.70;

    /**
     * The risk flag that means the user directed offence or aggression at the
     * assistant or at the service.
     *
     * <p>A flag rather than an {@link Intent}, for two reasons. It is orthogonal to
     * what the user wants — "seu lixo, qual o cep da paulista" is a data request and
     * an insult, and an intent would have to drop one of them. And the intent that
     * looks closest is the one it must never be confused with: the voice document
     * says outright that "a message expressing frustration with an answer is not an
     * offence", so a mandated de-escalation keyed off {@link Intent#FRUSTRATION}
     * would answer a complaint about a wrong CEP with a script.
     */
    public static final String OFFENCE_FLAG = "offence";

    private static final Pattern LANGUAGE_TAG = Pattern.compile("[a-zA-Z]{2,3}(-[a-zA-Z]{2,8}){0,2}");
    private static final Pattern RISK_FLAG = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final String DEFAULT_LANGUAGE = "pt-BR";

    /**
     * Every field here comes out of an LLM and two of them are interpolated into the
     * next prompt, so they are constrained rather than trusted. The guardrail chain
     * inspects the user's message, not this object — an unvalidated field would be a
     * way to get attacker-chosen text into the agent's prompt with no guardrail in
     * its path. Anything not matching is replaced, never sanitised in place.
     */
    public TriageVerdict {
        riskFlags = riskFlags == null ? List.of() : riskFlags.stream()
                .filter(flag -> flag != null && RISK_FLAG.matcher(flag).matches())
                .distinct()
                .limit(8)
                .toList();
        intent = intent == null ? Intent.UNKNOWN : intent;
        language = language != null && LANGUAGE_TAG.matcher(language).matches()
                ? language
                : DEFAULT_LANGUAGE;
        skillHint = skillHint == null ? "" : skillHint;
    }

    /**
     * The routing answer, with the asymmetry rule applied. Use this rather than
     * reading {@link #decision()} directly.
     */
    public boolean inScope() {
        return decision == Decision.IN_SCOPE || confidence < REFUSAL_CONFIDENCE_THRESHOLD;
    }

    /**
     * Whether the voice document's rule for an offensive message applies to this turn.
     *
     * <p>Read by {@code ChatTurnService} and carried into the invocation, because the
     * output guardrail enforces a sentence the document mandates for exactly this case
     * and cannot decide from the answer's text whether the <em>user</em> was abusive.
     */
    public boolean carriesOffence() {
        return riskFlags.contains(OFFENCE_FLAG);
    }

    /**
     * True when the model wanted to refuse but was not sure enough to be allowed to.
     */
    public boolean wasUpgradedToInScope() {
        return decision == Decision.OUT_OF_SCOPE && confidence < REFUSAL_CONFIDENCE_THRESHOLD;
    }

    /**
     * A copy whose skill hint is guaranteed to name a real skill. Checked against
     * the catalogue rather than a pattern: the hint is interpolated into the agent's
     * prompt, and the only safe values are ones the application already publishes.
     */
    public TriageVerdict withSkillHintIn(Collection<String> knownSkills) {
        return knownSkills.contains(skillHint)
                ? this
                : new TriageVerdict(decision, confidence, intent, language, "", riskFlags);
    }

    /**
     * A verdict the pipeline produces itself, without consulting the model.
     */
    static TriageVerdict deterministic(Decision decision, Intent intent, String language) {
        return new TriageVerdict(decision, 1.0, intent, language, "", List.of());
    }
}
