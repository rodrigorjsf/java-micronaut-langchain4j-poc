package io.github.rodrigorjsf.agenticchat.tools.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two precision decisions, each pinned by the wrong answer it prevents.
 *
 * <p>Both are invisible in ordinary use. A calculator that rounds every
 * intermediate to cents produces correct-looking output on every short example
 * anyone tries by hand, and only drifts on the long invoice nobody checks — so
 * the assertions here are written as "this must not equal the rounded-at-every-
 * step figure", naming that figure explicitly.
 */
class CalculatorPrecisionTest {

    /** {@code MathContext.DECIMAL128} carries 34 significant digits. */
    private static final int WORKING_DIGITS = 34;

    private final CalculatorTools calculator = new CalculatorTools();

    private String run(CalculationStep... steps) {
        return calculator.calculate(List.of(steps), Currency.BRL, Rounding.HALF_EVEN);
    }

    @Test
    @DisplayName("one divided by three does not throw")
    void aNonTerminatingDivisionSurvives() {
        // BigDecimal.divide with no MathContext throws ArithmeticException the moment
        // the expansion does not terminate, and 1/3 is the first case anybody hits.
        // Without the DECIMAL128 argument this call returns internal_error.
        String output = run(new CalculationStep("third", Operation.DIVIDE, List.of("1", "3")));

        assertThat(output).doesNotContain("CALCULATION FAILED");
        assertThat(output).endsWith("ANSWER: third = 0.33 BRL");
    }

    @Test
    @DisplayName("a rounded line carries the full-precision value beside it")
    void theExactValueIsShownWhenItDiffers() {
        String output = run(new CalculationStep("third", Operation.DIVIDE, List.of("1", "3")));

        assertThat(output).contains("= 0.33 exact=0." + "3".repeat(WORKING_DIGITS));
    }

    @Test
    @DisplayName("a figure already at scale 2 does not repeat itself as exact=")
    void theExactSuffixStaysOffWhenItAddsNothing() {
        // An invoice of whole cents that printed exact= on every line would train the
        // model to skip the field on the one line where it matters.
        String output = run(new CalculationStep("total", Operation.SUM, List.of("10.50", "20.25")));

        assertThat(output).doesNotContain("exact=");
        assertThat(output).contains("total       SUM              = 30.75");
    }

    @Test
    @DisplayName("an intermediate is carried forward unrounded, so ten thirds is ten")
    void intermediatesAreNotRoundedToCents() {
        // 10 / 3 = 3.333... . Rounded to cents that is 3.33, and 3.33 x 3 = 9.99.
        // Carried at working precision it is 9.999...9, which presents as 10.00.
        // The 0.01 between those two answers is the whole point of the decision, and
        // on a real invoice it is 0.01 per line rather than 0.01 per document.
        String output = run(
                new CalculationStep("share", Operation.DIVIDE, List.of("10", "3")),
                new CalculationStep("whole", Operation.MULTIPLY, List.of("#share", "3")));

        assertThat(output).endsWith("ANSWER: whole = 10.00 BRL");
        // Matched with the "= " prefix on purpose: the unrounded 9.999...9 does appear
        // on the line, as exact=, and that is the value being carried forward. What
        // must never appear is 9.99 as a step's presented figure.
        assertThat(output)
                .as("= 9.99 is what a calculator that rounds every intermediate presents")
                .doesNotContain("= 9.99");
    }

    @Test
    @DisplayName("the drift does not accumulate across a chain either")
    void aChainOfStepsStaysAtWorkingPrecision() {
        // Three thirds. Rounded at each step: 0.33 + 0.33 + 0.33 = 0.99. Unrounded:
        // 0.999...9, which presents as 1.00. This is the same decision as the test
        // above, measured across steps rather than through one multiply, because the
        // failure it guards against is additive and only shows up on length.
        String output = run(
                new CalculationStep("third", Operation.DIVIDE, List.of("1", "3")),
                new CalculationStep("two_thirds", Operation.SUM, List.of("#third", "#third")),
                new CalculationStep("three_thirds", Operation.SUM, List.of("#two_thirds", "#third")));

        assertThat(output).endsWith("ANSWER: three_thirds = 1.00 BRL");
        assertThat(output)
                .as("0.99 is the rounded-at-every-step answer")
                .doesNotContain("= 0.99");
    }

    @Test
    @DisplayName("a large magnitude is written out in full, never in scientific notation")
    void resultsAreAlwaysPlainStrings() {
        // pow and a large multiply both produce a negative scale, and BigDecimal's
        // toString switches to scientific notation once that happens. "1.0E+21" where
        // the answer should read "1000000000000000000000.00" is a wrong-looking total
        // with correct arithmetic behind it, and the model has no way to tell.
        String output = run(new CalculationStep(
                "big", Operation.COMPOUND_INTEREST, List.of("1000", "100", "60")));

        assertThat(output).doesNotContain("E+");
        assertThat(output).doesNotContain("CALCULATION FAILED");
        // 1000 x 2^60 = 1000 x 1152921504606846976 = 1152921504606846976000
        assertThat(output).endsWith("ANSWER: big = 1152921504606846976000.00 BRL");
    }

    @Test
    @DisplayName("the working precision is the presentation's ceiling, not its floor")
    void moreThanTwoDecimalsSurviveIntoTheNextStep() {
        // A rate expressed to four decimal places is the ordinary case in Brazilian
        // finance, and rounding it on arrival would make the tool wrong before the
        // first operation.
        String output = run(
                new CalculationStep("rate", Operation.SUM, List.of("0.1234")),
                new CalculationStep("charge", Operation.MULTIPLY, List.of("#rate", "10000")));

        assertThat(output).contains("rate        SUM              = 0.12 exact=0.1234");
        assertThat(output).endsWith("ANSWER: charge = 1234.00 BRL");
    }
}
