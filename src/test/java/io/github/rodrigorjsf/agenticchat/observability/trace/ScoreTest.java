package io.github.rodrigorjsf.agenticchat.observability.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The score value object: which bounds throw, which are repaired, and what goes on the wire.
 *
 * <p>The split is the interesting part and it is not arbitrary. A bound whose input is a
 * literal in the calling code throws, because the defect is at the call site and no repair
 * preserves the meaning. A bound whose input is runtime data is repaired, because a score
 * is recorded from inside the turn it measures and an observation must not be able to fail
 * the thing it is observing.
 */
class ScoreTest {

    @Test
    @DisplayName("each factory sets the data type its value belongs to")
    void eachFactorySetsItsDataType() {
        assertThat(Score.numeric("latency_ms", 912).dataType()).isEqualTo(Score.DataType.NUMERIC);
        assertThat(Score.bool("answered", true).dataType()).isEqualTo(Score.DataType.BOOLEAN);
        assertThat(Score.categorical("triage_decision", "IN_SCOPE").dataType())
                .isEqualTo(Score.DataType.CATEGORICAL);
        assertThat(Score.text("critique", "reads well").dataType()).isEqualTo(Score.DataType.TEXT);
    }

    @Test
    @DisplayName("a boolean score is exactly 1 or 0, and integral rather than 1.0")
    void aBooleanScoreIsOneOrZero() {
        assertThat(Score.bool("answered", true).wireValue()).isEqualTo(1);
        assertThat(Score.bool("answered", false).wireValue()).isEqualTo(0);
        // Not 1.0. Both are numbers to a lenient parser and only one is what the schema
        // documents, which is a difference that surfaces as a 400 on a background thread.
        assertThat(Score.bool("answered", true).wireValue()).isNotInstanceOf(Double.class);
    }

    @Test
    @DisplayName("a numeric score keeps its value unrounded on the wire")
    void aNumericScoreKeepsItsValue() {
        assertThat(Score.numeric("triage_confidence", 0.83).wireValue()).isEqualTo(0.83d);
    }

    @Test
    @DisplayName("only one half of the value union is ever populated")
    void exactlyOneHalfOfTheUnionIsSet() {
        var numeric = Score.numeric("latency_ms", 912);
        assertThat(numeric.stringValue()).isNull();

        var categorical = Score.categorical("triage_decision", "OUT_OF_SCOPE");
        assertThat(categorical.numericValue()).isNull();
        assertThat(categorical.wireValue()).isEqualTo("OUT_OF_SCOPE");
    }

    @Test
    @DisplayName("a text score past 500 characters is truncated, never rejected")
    void anOverlongTextScoreIsTruncated() {
        String longer = "a".repeat(Score.TEXT_MAX_CHARS + 120);

        var score = Score.text("critique", longer);

        // A TEXT score's obvious source is a model's own words. Throwing here would let a
        // verbose model fail the turn it was being graded on.
        assertThat(score.stringValue()).hasSize(Score.TEXT_MAX_CHARS);
        assertThat(score.wireValue()).isEqualTo("a".repeat(Score.TEXT_MAX_CHARS));
    }

    @Test
    @DisplayName("truncating a text score never cuts a character in half")
    void truncationLandsOnACodePointBoundary() {
        // 499 plain characters and one emoji, written as escapes so the assertion does not
        // also depend on the compiler's source encoding. The naive cut at 500 would keep
        // the high surrogate and drop its pair, leaving an unpaired surrogate on the wire.
        String withSurrogatePair = "a".repeat(Score.TEXT_MAX_CHARS - 1) + "\uD83D\uDE00";
        assertThat(withSurrogatePair).hasSize(Score.TEXT_MAX_CHARS + 1);

        String truncated = Score.text("critique", withSurrogatePair).stringValue();

        assertThat(truncated).hasSize(Score.TEXT_MAX_CHARS - 1);
        assertThat(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("a text score that fits is left exactly as it was written")
    void aTextScoreThatFitsIsUntouched() {
        String exact = "a".repeat(Score.TEXT_MAX_CHARS);
        assertThat(Score.text("critique", exact).stringValue()).isEqualTo(exact);
    }

    @Test
    @DisplayName("a score with no name is refused: the name is a literal at the call site")
    void anUnnamedScoreIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> Score.numeric("  ", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> Score.categorical(null, "IN_SCOPE"));
    }

    @Test
    @DisplayName("a non-finite numeric score is refused before it can become invalid JSON")
    void aNonFiniteNumberIsRefused() {
        // NaN and Infinity have no JSON representation. Sending one would have Langfuse
        // reject the write rather than store a wrong number, so there is nothing to repair.
        assertThatIllegalArgumentException().isThrownBy(() -> Score.numeric("ratio", Double.NaN));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Score.numeric("ratio", Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("a categorical or text score with no value is refused")
    void anEmptyStringValuedScoreIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> Score.categorical("triage_decision", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> Score.text("critique", null));
    }

    @Test
    @DisplayName("a boolean score cannot be given a value other than 1 or 0")
    void aBooleanScoreCannotCarryAnArbitraryNumber() {
        // The factory takes a boolean so a caller cannot reach this, but the canonical
        // constructor is public and the invariant belongs to the type, not to the factory.
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Score("answered", Score.DataType.BOOLEAN, 0.5d, null, null));
    }

    @Test
    @DisplayName("a comment is carried without disturbing the value")
    void aCommentDoesNotDisturbTheValue() {
        var commented = Score.categorical("triage_decision", "OUT_OF_SCOPE").withComment("no tool covers legal advice");

        assertThat(commented.comment()).isEqualTo("no tool covers legal advice");
        assertThat(commented.wireValue()).isEqualTo("OUT_OF_SCOPE");
        assertThat(commented.dataType()).isEqualTo(Score.DataType.CATEGORICAL);
        assertThatCode(() -> Score.numeric("latency_ms", 912).withComment(null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a correction is the fixed name and data type the Corrections feature reads")
    void aCorrectionIsNamedOutput() {
        var correction = Score.correction("Two eggs, not three.");

        // Both halves are fixed by the feature, not by the caller. Langfuse renders a
        // corrected output only for a score whose name is exactly "output" and whose data
        // type is CORRECTION; either one spelt differently files an ordinary score that
        // never appears in the diff view, with no error anywhere.
        assertThat(correction.name()).isEqualTo("output");
        assertThat(correction.dataType()).isEqualTo(Score.DataType.CORRECTION);
        assertThat(correction.wireValue()).isEqualTo("Two eggs, not three.");
    }

    @Test
    @DisplayName("a correction is NOT truncated at the TEXT ceiling")
    void aCorrectionIsNotTruncated() {
        // A TEXT score is a critique and survives being cut. A correction is the output
        // the model should have produced, and a cut one is a wrong training example that
        // reads as a right one. The ceiling belongs to TEXT alone.
        String long_ = "x".repeat(Score.TEXT_MAX_CHARS + 200);

        assertThat(Score.correction(long_).wireValue()).isEqualTo(long_);
        assertThat(Score.text("critique", long_).stringValue()).hasSize(Score.TEXT_MAX_CHARS);
    }

    @Test
    @DisplayName("an empty correction is a defect at the call site, not a repairable value")
    void anEmptyCorrectionThrows() {
        assertThatIllegalArgumentException().isThrownBy(() -> Score.correction("  "));
    }
}
