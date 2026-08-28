package io.github.rodrigorjsf.agenticchat.tools.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One case per operation in the frozen set of sixteen, each with a
 * hand-computed expected value.
 *
 * <p>The three that cannot be checked at a glance — {@code PERCENT_CHANGE},
 * {@code COMPOUND_INTEREST} and {@code INSTALLMENT_PAYMENT} — carry the working
 * in a comment. A fixture nobody can re-derive is a fixture that gets updated to
 * whatever the code now prints the first time someone breaks the formula.
 */
class CalculatorArithmeticTest {

    private final CalculatorTools calculator = new CalculatorTools();

    /** One step named {@code result}, so every assertion below reads the same way. */
    private String answerFor(Operation operation, String... operands) {
        String output = calculator.calculate(
                List.of(new CalculationStep("result", operation, List.of(operands))),
                Currency.BRL,
                Rounding.HALF_EVEN);
        assertThat(output).doesNotContain("CALCULATION FAILED");
        return output.substring(output.lastIndexOf('\n') + 1);
    }

    @Test
    void sumAddsEveryOperand() {
        assertThat(answerFor(Operation.SUM, "10.50", "20.25", "1000"))
                .isEqualTo("ANSWER: result = 1030.75 BRL");
    }

    @Test
    void subtractTakesEveryLaterOperandOffTheFirst() {
        // 1000 - 250 - 50.50
        assertThat(answerFor(Operation.SUBTRACT, "1000", "250", "50.50"))
                .isEqualTo("ANSWER: result = 699.50 BRL");
    }

    @Test
    void multiplyIsTheProduct() {
        // 12.5 x 4 x 2
        assertThat(answerFor(Operation.MULTIPLY, "12.5", "4", "2"))
                .isEqualTo("ANSWER: result = 100.00 BRL");
    }

    @Test
    void divideGoesLeftToRight() {
        // 1000 / 4 / 5 = 50, and not 1000 / (4 / 5) = 1250
        assertThat(answerFor(Operation.DIVIDE, "1000", "4", "5"))
                .isEqualTo("ANSWER: result = 50.00 BRL");
    }

    @Test
    void negateFlipsTheSign() {
        assertThat(answerFor(Operation.NEGATE, "1234.56"))
                .isEqualTo("ANSWER: result = -1234.56 BRL");
    }

    @Test
    void averageIsTheArithmeticMean() {
        // (10 + 20 + 33) / 3 = 63 / 3 = 21
        assertThat(answerFor(Operation.AVERAGE, "10", "20", "33"))
                .isEqualTo("ANSWER: result = 21.00 BRL");
    }

    @Test
    void minIsTheSmallestOperand() {
        assertThat(answerFor(Operation.MIN, "10.5", "3.25", "99"))
                .isEqualTo("ANSWER: result = 3.25 BRL");
    }

    @Test
    void maxIsTheLargestOperand() {
        assertThat(answerFor(Operation.MAX, "10.5", "3.25", "99"))
                .isEqualTo("ANSWER: result = 99.00 BRL");
    }

    @Test
    @DisplayName("PERCENT_OF takes [percent, base] — '15% of 200' is 30")
    void percentOfTakesThePercentFirst() {
        assertThat(answerFor(Operation.PERCENT_OF, "15", "200"))
                .isEqualTo("ANSWER: result = 30.00 BRL");
    }

    @Test
    @DisplayName("ADD_PERCENT takes [base, percent] — '200 plus 15%' is 230")
    void addPercentTakesTheBaseFirst() {
        assertThat(answerFor(Operation.ADD_PERCENT, "200", "15"))
                .isEqualTo("ANSWER: result = 230.00 BRL");
    }

    @Test
    @DisplayName("the mirrored operand order is a silent wrong answer, not an error")
    void swappingAddPercentOperandsIsNotDetectable() {
        // The reason the trap is stated in the @Tool description, in the appendix and
        // in Operation's javadoc: the tool cannot tell the two apart. ["15","200"] is
        // 15 x (1 + 200/100) = 45, which is a perfectly well-formed answer to a
        // question nobody asked. Nothing here can be fixed in code; it is fixed in
        // documentation or it is not fixed.
        assertThat(answerFor(Operation.ADD_PERCENT, "15", "200"))
                .isEqualTo("ANSWER: result = 45.00 BRL");
    }

    @Test
    void subtractPercentIsADiscount() {
        // 200 x (1 - 15/100) = 200 x 0.85 = 170
        assertThat(answerFor(Operation.SUBTRACT_PERCENT, "200", "15"))
                .isEqualTo("ANSWER: result = 170.00 BRL");
    }

    @Test
    @DisplayName("PERCENT_CHANGE returns percent units, not a fraction")
    void percentChangeIsInPercentUnits() {
        // (230 - 200) / 200 x 100 = 30 / 200 x 100 = 0.15 x 100 = 15.
        // The x100 is what makes this 15.00 and not 0.15, and dropping it is the
        // mistake this fixture exists to catch.
        assertThat(answerFor(Operation.PERCENT_CHANGE, "200", "230"))
                .isEqualTo("ANSWER: result = 15.00 BRL");
    }

    @Test
    void percentChangeIsNegativeWhenTheFigureFell() {
        // (170 - 200) / 200 x 100 = -30 / 200 x 100 = -15
        assertThat(answerFor(Operation.PERCENT_CHANGE, "200", "170"))
                .isEqualTo("ANSWER: result = -15.00 BRL");
    }

    @Test
    void ratioPercentIsTheShareOfAWhole() {
        // 50 / 200 x 100 = 25
        assertThat(answerFor(Operation.RATIO_PERCENT, "50", "200"))
                .isEqualTo("ANSWER: result = 25.00 BRL");
    }

    @Test
    void simpleInterestDoesNotCompound() {
        // P x (1 + i x n) = 1000 x (1 + 0.02 x 12) = 1000 x 1.24 = 1240
        assertThat(answerFor(Operation.SIMPLE_INTEREST, "1000", "2", "12"))
                .isEqualTo("ANSWER: result = 1240.00 BRL");
    }

    @Test
    @DisplayName("COMPOUND_INTEREST returns the final amount, principal included")
    void compoundInterestCompounds() {
        // P x (1 + i)^n = 1000 x 1.02^12.
        //   1.02^2  = 1.0404
        //   1.02^4  = 1.0404^2            = 1.08243216
        //   1.02^8  = 1.08243216^2        = 1.1716593810022656
        //   1.02^12 = 1.02^8 x 1.02^4     = 1.268241794562545...
        // x 1000 = 1268.241794..., which HALF_EVEN at scale 2 shows as 1268.24.
        // Compare with SIMPLE_INTEREST on the same three operands: 1240.00. The
        // 28.24 between them is the whole difference the two operations exist for.
        assertThat(answerFor(Operation.COMPOUND_INTEREST, "1000", "2", "12"))
                .isEqualTo("ANSWER: result = 1268.24 BRL");
    }

    @Test
    @DisplayName("INSTALLMENT_PAYMENT returns the payment per period, not the total")
    void installmentPaymentUsesThePriceTable() {
        // PMT = P x i x (1+i)^n / ((1+i)^n - 1), with P = 1000, i = 0.02, n = 12.
        //   (1+i)^n            = 1.268241794562545   (worked above)
        //   numerator          = 1000 x 0.02 x 1.268241794562545 = 25.36483589125090
        //   denominator        = 0.268241794562545
        //   PMT                = 25.36483589... / 0.268241794... = 94.5596...
        // Sanity check on the shape rather than on the arithmetic: 12 x 94.56 =
        // 1134.72, comfortably above the 1000 borrowed and below the 1268.24 that
        // COMPOUND_INTEREST charges for leaving the whole sum outstanding all year.
        assertThat(answerFor(Operation.INSTALLMENT_PAYMENT, "1000", "2", "12"))
                .isEqualTo("ANSWER: result = 94.56 BRL");
    }

    @Test
    @DisplayName("a zero rate makes the instalment plan a plain division")
    void installmentPaymentWithNoInterestSplitsThePrincipal() {
        // The Price formula collapses to 0/0 at i = 0, and an interest-free plan is
        // an ordinary question rather than an error.
        assertThat(answerFor(Operation.INSTALLMENT_PAYMENT, "1200", "0", "12"))
                .isEqualTo("ANSWER: result = 100.00 BRL");
    }

    @Test
    @DisplayName("a later step reads an earlier one through #id")
    void stepsChainThroughReferences() {
        String output = calculator.calculate(
                List.of(
                        new CalculationStep("subtotal", Operation.SUM, List.of("100", "200")),
                        new CalculationStep("total", Operation.ADD_PERCENT, List.of("#subtotal", "10"))),
                Currency.BRL,
                Rounding.HALF_EVEN);

        // 300 + 10% = 330
        assertThat(output).endsWith("ANSWER: total = 330.00 BRL");
    }

    @Test
    @DisplayName("the result format is exactly the one the contract froze")
    void theResultFormatMatchesTheContract() {
        // Byte for byte, including the two column widths. This is the text the model
        // reads and quotes back to the user, so a change to it is a change to the
        // product, not a formatting tidy-up. 1350 less 5% is 1282.50; plus 8% is
        // 1385.10.
        String output = calculator.calculate(
                List.of(
                        new CalculationStep("subtotal", Operation.SUM, List.of("1350")),
                        new CalculationStep("discount", Operation.SUBTRACT_PERCENT, List.of("#subtotal", "5")),
                        new CalculationStep("tax", Operation.ADD_PERCENT, List.of("#discount", "8"))),
                Currency.BRL,
                Rounding.HALF_EVEN);

        assertThat(output).isEqualTo("""
                CALCULATION · currency=BRL · rounding=HALF_EVEN · scale=2
                subtotal    SUM              = 1350.00
                discount    SUBTRACT_PERCENT = 1282.50
                tax         ADD_PERCENT      = 1385.10
                ANSWER: tax = 1385.10 BRL""");
    }

    @Test
    @DisplayName("the header states the currency and the rounding actually applied")
    void theHeaderReportsWhatWasUsed() {
        // The safety argument for defaulting rather than rejecting: a mode the model
        // can read out loud is a mode the user can correct.
        String output = calculator.calculate(
                List.of(new CalculationStep("half", Operation.DIVIDE, List.of("5", "2"))),
                Currency.USD,
                Rounding.HALF_UP);

        assertThat(output).startsWith("CALCULATION · currency=USD · rounding=HALF_UP · scale=2");
        assertThat(output).endsWith("ANSWER: half = 2.50 USD");
    }

    @Test
    @DisplayName("the rounding mode changes the presented figure and nothing else")
    void theRoundingModeIsHonoured() {
        // 2.675 is the textbook tie. HALF_EVEN rounds to the even digit, HALF_UP away
        // from zero, and FLOOR does not look at the tie at all.
        assertThat(answerFor(Operation.SUM, "2.675")).isEqualTo("ANSWER: result = 2.68 BRL");

        var step = List.of(new CalculationStep("result", Operation.SUM, List.of("2.665")));
        assertThat(calculator.calculate(step, Currency.BRL, Rounding.HALF_EVEN))
                .endsWith("ANSWER: result = 2.66 BRL");
        assertThat(calculator.calculate(step, Currency.BRL, Rounding.HALF_UP))
                .endsWith("ANSWER: result = 2.67 BRL");
        assertThat(calculator.calculate(step, Currency.BRL, Rounding.FLOOR))
                .endsWith("ANSWER: result = 2.66 BRL");
    }

    @Test
    @DisplayName("a null rounding falls back to HALF_EVEN rather than throwing")
    void aMissingRoundingModeIsDefaulted() {
        // The framework substitutes the @P default at the tool door; a direct Java
        // caller does not, and a NullPointerException here would reach the model as
        // internal_error for a call that was perfectly fine.
        String output = calculator.calculate(
                List.of(new CalculationStep("result", Operation.SUM, List.of("1", "2"))), Currency.BRL, null);

        assertThat(output).startsWith("CALCULATION · currency=BRL · rounding=HALF_EVEN · scale=2");
    }
}
