package io.github.rodrigorjsf.agenticchat.observability.trace;

/**
 * One evaluation attached to a trace or an observation, in Langfuse's Scores vocabulary.
 *
 * <p><b>Not the injection score.</b> {@code InjectionHeuristics.Score} in
 * {@code guardrail.input} is the deterministic prompt-injection signal and has nothing to
 * do with this type. This one is a Langfuse record: a named measurement filed against an
 * observation so the UI can chart it, filter by it and compare two prompts on it. The
 * injection signal is one of the things that would be <em>reported</em> as one.
 *
 * <p><b>A score is not a span.</b> Langfuse's own migration guide is explicit — "Score
 * event -&gt; The dedicated Scores SDK or API, not an OTLP trace span" — which is why this
 * value object exists at all rather than being a handful of span attributes.
 *
 * <p>The factories are the way in, and each one sets the {@link DataType} its value
 * belongs to. That pairing is the whole point: {@code POST /api/public/scores} accepts a
 * number for {@code NUMERIC} and {@code BOOLEAN} and a string for {@code CATEGORICAL} and
 * {@code TEXT}, and a mismatch is a 400 discovered on a background thread, hours after the
 * turn it was measuring.
 *
 * <h2>Two kinds of bound, treated differently on purpose</h2>
 * <p>A bound whose input is a literal in the calling code <b>throws</b>: a blank score
 * name, a non-finite number. Those are defects at the call site and there is no repair
 * that preserves meaning — a score called {@code ""} measures nothing, and {@code NaN} is
 * not even valid JSON, so letting it through would have Langfuse reject the write rather
 * than store a wrong number.
 *
 * <p>A bound whose input is <b>runtime data is repaired</b>: {@code TEXT} past the
 * documented 500 characters is truncated, never rejected. A TEXT score's obvious source is
 * a model's own words, and this repository has already paid for the opposite choice — a
 * cosmetic rule that can withhold an answer is the {@code strYoutube} failure in another
 * costume. An observation must not be able to fail the turn it is observing.
 *
 * <p>Field names and constraints read 2026-08-22 from
 * {@code cloud.langfuse.com/generated/api/openapi.yml}.
 *
 * @param name         what is being measured, e.g. {@code triage_confidence}
 * @param dataType     which half of the value union is populated
 * @param numericValue set for {@code NUMERIC} and {@code BOOLEAN}, null otherwise
 * @param stringValue  set for {@code CATEGORICAL}, {@code TEXT} and {@code CORRECTION},
 *                     null otherwise
 * @param comment      free text shown beside the score, or null
 */
public record Score(String name,
                    DataType dataType,
                    Double numericValue,
                    String stringValue,
                    String comment) {

    /**
     * The documented ceiling on a {@code TEXT} score's value.
     */
    static final int TEXT_MAX_CHARS = 500;

    /**
     * The name Langfuse matches a corrected output on. Fixed by the feature, not chosen.
     */
    static final String CORRECTION_NAME = "output";

    /**
     * The five values the Scores API accepts for {@code dataType}.
     *
     * <p><b>Upper case on the wire</b>, unlike {@link ObservationType}, which Langfuse
     * reads in lower case. Same server, two spellings, and neither one errors when it is
     * wrong — the score is simply rejected or filed as something else. Hence
     * {@link #name()} being the wire form here and a separate {@code wireValue()} there.
     */
    public enum DataType {
        NUMERIC,
        BOOLEAN,
        CATEGORICAL,
        TEXT,
        /**
         * A corrected output — what the model should have produced. Written by
         * {@link Score#correction(String)}; see its javadoc for why the name is fixed.
         */
        CORRECTION
    }

    public Score {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a score must be named");
        }
        if (dataType == null) {
            throw new IllegalArgumentException("a score must declare its data type");
        }
        switch (dataType) {
            case NUMERIC, BOOLEAN -> {
                if (numericValue == null || !Double.isFinite(numericValue)) {
                    throw new IllegalArgumentException(
                            dataType + " score '" + name + "' needs a finite number");
                }
                if (dataType == DataType.BOOLEAN && numericValue != 0d && numericValue != 1d) {
                    throw new IllegalArgumentException(
                            "a BOOLEAN score is exactly 1 or 0, not " + numericValue);
                }
                // Exactly one half of the union is populated, so equality and the wire
                // form cannot disagree about which one carries the value.
                stringValue = null;
            }
            case CATEGORICAL, TEXT, CORRECTION -> {
                if (stringValue == null || stringValue.isBlank()) {
                    throw new IllegalArgumentException(
                            dataType + " score '" + name + "' needs a value");
                }
                if (dataType == DataType.TEXT) {
                    stringValue = truncate(stringValue);
                }
                numericValue = null;
            }
        }
    }

    /**
     * Any finite number: a probability, a latency, a token count, a rating.
     */
    public static Score numeric(String name, double value) {
        return new Score(name, DataType.NUMERIC, value, null, null);
    }

    /**
     * A pass/fail. Takes a {@code boolean} rather than a number, so the API's "must be
     * exactly 1 or 0" cannot be violated by a caller at all.
     */
    public static Score bool(String name, boolean flag) {
        return new Score(name, DataType.BOOLEAN, flag ? 1d : 0d, null, null);
    }

    /**
     * One label from a small fixed set — a verdict, an outcome, a route. Categorical
     * rather than text because Langfuse groups and charts these, and cannot group free
     * text.
     */
    public static Score categorical(String name, String category) {
        return new Score(name, DataType.CATEGORICAL, null, category, null);
    }

    /**
     * Free text, truncated at {@value #TEXT_MAX_CHARS} characters rather than rejected —
     * see the class comment on which bounds are repaired and why.
     */
    public static Score text(String name, String value) {
        return new Score(name, DataType.TEXT, null, value, null);
    }

    /**
     * The output the model <em>should</em> have produced, in the exact shape Langfuse's
     * Corrections feature reads: {@code name} is the literal {@code "output"} and the data
     * type is {@link DataType#CORRECTION}. Neither is the caller's to choose — a
     * correction filed under any other name is an ordinary score that never reaches the
     * diff view, and nothing anywhere reports that.
     *
     * <p>Not truncated, unlike {@link #text}. A cut critique is still a critique; a cut
     * correction is a wrong answer that reads as a right one, and its whole purpose is to
     * become a fine-tuning example.
     *
     * <p>Contract read 2026-08-23 from
     * {@code https://langfuse.com/docs/observability/features/corrections}.
     */
    public static Score correction(String correctedOutput) {
        return new Score(CORRECTION_NAME, DataType.CORRECTION, null, correctedOutput, null);
    }

    /**
     * @return a copy carrying {@code comment}, which Langfuse shows beside the value — the
     * place to put the reason for a verdict that the value alone cannot express
     */
    public Score withComment(String comment) {
        return new Score(name, dataType, numericValue, stringValue, comment);
    }

    /**
     * The value as the Scores API expects to receive it.
     *
     * <p>A {@code BOOLEAN} is narrowed to an {@code int} because a {@code Double}
     * serialises as {@code 1.0}, and the schema documents the value of a boolean score as
     * {@code 1} or {@code 0}. Both are numbers to a lenient parser and only one of them is
     * what the contract says, which is the kind of difference that surfaces as a 400 from
     * a background thread nobody is reading.
     */
    public Object wireValue() {
        return switch (dataType) {
            case BOOLEAN -> numericValue.intValue();
            case NUMERIC -> numericValue;
            case CATEGORICAL, TEXT, CORRECTION -> stringValue;
        };
    }

    /**
     * Cuts on a code-point boundary, so a truncated score never ends in half a character.
     */
    private static String truncate(String value) {
        if (value.length() <= TEXT_MAX_CHARS) {
            return value;
        }
        int end = TEXT_MAX_CHARS;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
