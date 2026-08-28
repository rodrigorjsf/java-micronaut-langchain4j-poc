package io.github.rodrigorjsf.agenticchat.tools.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One case per name in the closed error set, asserting the <b>text</b> that comes
 * back.
 *
 * <p>That text is not a log line: it is the next thing the model reads, and the
 * next call it makes is written from it. So each case checks three things — that
 * the failure is named, that the failing step is identified where one exists, and
 * that the correction says what to send instead. An error message that names a
 * problem without naming its fix produces a retry of the same call.
 *
 * <p>Every name in the set has a case here for a second reason: an error nobody
 * can construct an input for is imaginary, and the honest response to that is to
 * delete it from the set rather than to leave a branch in the code that documents
 * a state the code cannot reach.
 */
class CalculatorTaxonomyTest {

    private final CalculatorTools calculator = new CalculatorTools();

    private String run(CalculationStep... steps) {
        return calculator.calculate(List.of(steps), Currency.BRL, Rounding.HALF_EVEN);
    }

    @Test
    @DisplayName("empty_program — no steps at all")
    void emptyProgram() {
        String output = calculator.calculate(List.of(), Currency.BRL, Rounding.HALF_EVEN);

        assertThat(output).startsWith("CALCULATION FAILED · empty_program");
        assertThat(output).contains("nothing to calculate");
        assertThat(output).contains("correction: send at least one step");
    }

    @Test
    @DisplayName("empty_program — a null list is the same failure, not an exception")
    void aNullStepListIsAlsoEmptyProgram() {
        // The tool contract is that nothing throws. A null arrives whenever a provider
        // omits a required property, which is exactly the moment a 500 would be worst.
        assertThat(calculator.calculate(null, Currency.BRL, Rounding.HALF_EVEN))
                .startsWith("CALCULATION FAILED · empty_program");
    }

    @Test
    @DisplayName("too_many_steps — 51 steps against a limit of 50")
    void tooManySteps() {
        List<CalculationStep> steps = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            steps.add(new CalculationStep("s" + i, Operation.SUM, List.of("1")));
        }

        String output = calculator.calculate(steps, Currency.BRL, Rounding.HALF_EVEN);

        assertThat(output).startsWith("CALCULATION FAILED · too_many_steps");
        assertThat(output).contains("51 steps were sent and the limit is 50");
        assertThat(output).contains("split the work across more than one call");
    }

    @Test
    @DisplayName("invalid_step_id — capitals and a space are not in the grammar")
    void invalidStepId() {
        String output = run(new CalculationStep("Total Geral", Operation.SUM, List.of("1")));

        assertThat(output).startsWith("CALCULATION FAILED · invalid_step_id");
        assertThat(output).contains("'Total Geral'");
        assertThat(output).contains("lower-case letters, digits and underscores only");
    }

    @Test
    @DisplayName("invalid_step_id — a null step is reported by its position")
    void aNullStepIsReportedByPosition() {
        String output = calculator.calculate(
                Collections.singletonList(null), Currency.BRL, Rounding.HALF_EVEN);

        assertThat(output).startsWith("CALCULATION FAILED · invalid_step_id");
        assertThat(output).contains("step number 1 is empty");
    }

    @Test
    @DisplayName("duplicate_step_id — two steps claiming the same #reference target")
    void duplicateStepId() {
        String output = run(
                new CalculationStep("total", Operation.SUM, List.of("1")),
                new CalculationStep("total", Operation.SUM, List.of("2")));

        assertThat(output).startsWith("CALCULATION FAILED · duplicate_step_id");
        assertThat(output).contains("step: total");
        assertThat(output).contains("#total reference would be ambiguous");
        assertThat(output).contains("'total_2'");
    }

    @Test
    @DisplayName("unknown_operation — no operation was given, and the message does not pretend one was")
    void unknownOperation() {
        String output = run(new CalculationStep("total", null, List.of("1")));

        assertThat(output).startsWith("CALCULATION FAILED · unknown_operation");
        assertThat(output).contains("step: total");
        // The branch fires on a null, so the text must not claim to have seen a name.
        // It lists the sixteen instead, which is the only thing that gets the next
        // call right.
        assertThat(output).contains("did not name an operation");
        assertThat(output).contains("SUM, SUBTRACT, MULTIPLY, DIVIDE");
        assertThat(output).contains("INSTALLMENT_PAYMENT");
    }

    @Test
    @DisplayName("wrong_operand_count — SUBTRACT needs two, and the fix names the slots")
    void wrongOperandCount() {
        String output = run(new CalculationStep("less", Operation.SUBTRACT, List.of("100")));

        assertThat(output).startsWith("CALCULATION FAILED · wrong_operand_count");
        assertThat(output).contains("SUBTRACT was sent 1 operand and it takes 2 or more operands");
    }

    @Test
    @DisplayName("wrong_operand_count — the correction spells out the mirrored percent order")
    void theCorrectionCarriesTheOperandOrder() {
        // The likelier mistake on a two-operand percent step is the order, not the
        // count, so the count error is where the order gets restated.
        String output = run(new CalculationStep("plus", Operation.ADD_PERCENT, List.of("200")));

        assertThat(output).contains("in the order [base, percent]");
    }

    @Test
    @DisplayName("no_operands — an empty list, named for what it is")
    void noOperands() {
        // The contract routes "AVERAGE with no operands" to division_by_zero. It cannot
        // get there: the operand-count guard runs first, and no_operands names the real
        // problem — the model sent an empty array, it did not divide by anything.
        String output = run(new CalculationStep("mean", Operation.AVERAGE, List.of()));

        assertThat(output).startsWith("CALCULATION FAILED · no_operands");
        assertThat(output).contains("step: mean");
        assertThat(output).contains("AVERAGE takes 1 or more operands");
    }

    @Test
    @DisplayName("too_many_operands — 201 against a limit of 200, with a batching fix")
    void tooManyOperands() {
        List<String> operands = Collections.nCopies(201, "1");

        String output = run(new CalculationStep("total", Operation.SUM, operands));

        assertThat(output).startsWith("CALCULATION FAILED · too_many_operands");
        assertThat(output).contains("201 operands were sent and the limit per step is 200");
        assertThat(output).contains("add them in batches");
    }

    @Test
    @DisplayName("invalid_number — a pt-BR numeral, corrected without being guessed at")
    void invalidNumber() {
        String output = run(new CalculationStep("total", Operation.SUM, List.of("1.234,56")));

        assertThat(output).startsWith("CALCULATION FAILED · invalid_number");
        assertThat(output).contains("the operand '1.234,56' is not a number this tool accepts");
        assertThat(output).contains("no thousands separator");
        // Both separators are present, so the decimal one is the last one and the fix
        // is derived rather than chosen.
        assertThat(output).contains("Write '1.234,56' as '1234.56'");
    }

    @Test
    @DisplayName("invalid_number — a currency symbol is stripped in the suggestion")
    void aCurrencySymbolIsCorrected() {
        String output = run(new CalculationStep("total", Operation.SUM, List.of("R$ 1234.56")));

        assertThat(output).startsWith("CALCULATION FAILED · invalid_number");
        assertThat(output).contains("as '1234.56'");
    }

    @Test
    @DisplayName("invalid_number — a lone comma is ambiguous, so both readings are offered")
    void aLoneCommaIsNeverResolvedForTheModel() {
        // 1,234 is 1.234 in pt-BR and 1234 in en-US. Choosing one here would be the
        // same guess the strict grammar exists to refuse, one layer further in.
        String output = run(new CalculationStep("total", Operation.SUM, List.of("1,234")));

        assertThat(output).startsWith("CALCULATION FAILED · invalid_number");
        assertThat(output).contains("send '1.234' if it was a decimal comma");
        assertThat(output).contains("or '1234' if it separated thousands");
    }

    @Test
    @DisplayName("invalid_number — an exponent is expanded, never edited character by character")
    void anExponentIsExpandedNotStripped() {
        // Deleting the 'e' from 1e3 gives 13. The suggestion goes through BigDecimal
        // so that it stays three orders of magnitude away from that.
        String output = run(new CalculationStep("total", Operation.SUM, List.of("1e3")));

        assertThat(output).startsWith("CALCULATION FAILED · invalid_number");
        assertThat(output).contains("Write '1e3' as '1000'");
        assertThat(output).doesNotContain("as '13'");
    }

    @Test
    @DisplayName("invalid_number — an exponent too large to write out is named, not expanded")
    void anUnwritableExponentIsNamedRatherThanPrinted() {
        // The correction goes through BigDecimal, and BigDecimal will happily expand a
        // five-character operand into half a megabyte: the length check at 40
        // characters passes before the grammar is ever consulted, so '1e10000000'
        // reached toPlainString and came back as a 10,000,309-character error message
        // — the next thing the model reads — while '1e999999999' exhausted the heap.
        // The bound is the one the tool already enforces on operands: a correction the
        // tool would itself reject is not a correction.
        String output = run(new CalculationStep("total", Operation.SUM, List.of("1e500")));

        assertThat(output).startsWith("CALCULATION FAILED · invalid_number");
        assertThat(output).contains("no exponent");
        assertThat(output).contains("501 characters long");
        assertThat(output).doesNotContain("0".repeat(100));
        assertThat(output.length()).isLessThan(600);
    }

    @Test
    @DisplayName("invalid_number — zero at a huge exponent is still zero, not an unsendable figure")
    void zeroIsNeverTooLargeToWriteDown() {
        // The length of the expansion is computed from precision and scale rather than
        // by building the string, and 0e500 is the one value where those two do not
        // describe what BigDecimal actually writes: it short-circuits to "0". Without
        // that branch the model is told the number zero is 501 characters long and
        // cannot be sent, which is a correction it has no way to act on.
        String output = run(new CalculationStep("total", Operation.SUM, List.of("0e500")));

        assertThat(output).startsWith("CALCULATION FAILED · invalid_number");
        assertThat(output).contains("Write '0e500' as '0'");
        assertThat(output).doesNotContain("cannot be sent at all");
    }

    @Test
    @DisplayName("invalid_number — an operand longer than 40 characters is refused before parsing")
    void anOverlongOperandIsRefused() {
        String output = run(new CalculationStep("total", Operation.SUM, List.of("1".repeat(41))));

        assertThat(output).startsWith("CALCULATION FAILED · invalid_number");
        assertThat(output).contains("41 characters long and the limit is 40");
    }

    @Test
    @DisplayName("unknown_reference — a forward reference, with the available ids listed")
    void unknownReferenceForwards() {
        String output = run(
                new CalculationStep("first", Operation.SUM, List.of("#second")),
                new CalculationStep("second", Operation.SUM, List.of("1")));

        assertThat(output).startsWith("CALCULATION FAILED · unknown_reference");
        assertThat(output).contains("step: first");
        assertThat(output).contains("'#second' does not name any step that has already run");
        assertThat(output).contains("BEFORE this one");
        assertThat(output).contains("nothing yet, this is the first step");
    }

    @Test
    @DisplayName("unknown_reference — a self reference is the same check, with no cycle detection")
    void unknownReferenceToItself() {
        // A cycle is unrepresentable because #id only ever resolves backwards, so this
        // needs no graph analysis and gets none.
        String output = run(
                new CalculationStep("base", Operation.SUM, List.of("10")),
                new CalculationStep("loop", Operation.SUM, List.of("#loop")));

        assertThat(output).startsWith("CALCULATION FAILED · unknown_reference");
        assertThat(output).contains("step: loop");
        assertThat(output).contains("Available here: #base");
    }

    @Test
    @DisplayName("division_by_zero — DIVIDE by a zero operand")
    void divisionByZero() {
        String output = run(new CalculationStep("share", Operation.DIVIDE, List.of("10", "0")));

        assertThat(output).startsWith("CALCULATION FAILED · division_by_zero");
        assertThat(output).contains("step: share");
        assertThat(output).contains("a divisor in this DIVIDE step is zero");
    }

    @Test
    @DisplayName("division_by_zero — PERCENT_CHANGE from zero has no percentage")
    void percentChangeFromZero() {
        String output = run(new CalculationStep("growth", Operation.PERCENT_CHANGE, List.of("0", "50")));

        assertThat(output).startsWith("CALCULATION FAILED · division_by_zero");
        assertThat(output).contains("divides by the 'from' value");
        assertThat(output).contains("Report the absolute difference instead");
    }

    @Test
    @DisplayName("division_by_zero — RATIO_PERCENT of a zero whole")
    void ratioOfZero() {
        String output = run(new CalculationStep("share", Operation.RATIO_PERCENT, List.of("50", "0")));

        assertThat(output).startsWith("CALCULATION FAILED · division_by_zero");
        assertThat(output).contains("divides by the 'whole'");
    }

    @Test
    @DisplayName("division_by_zero — an instalment plan with no instalments")
    void installmentPaymentOverZeroPeriods() {
        // 0 is inside the 0..1200 bound, so this is not invalid_periods: the number is
        // legal and the division it implies is not.
        String output = run(new CalculationStep(
                "payment", Operation.INSTALLMENT_PAYMENT, List.of("1000", "2", "0")));

        assertThat(output).startsWith("CALCULATION FAILED · division_by_zero");
        assertThat(output).contains("asked for 0 periods");
        assertThat(output).contains("at least 1");
    }

    @Test
    @DisplayName("invalid_periods — a fraction of a period is not a period")
    void fractionalPeriods() {
        String output = run(new CalculationStep(
                "grown", Operation.COMPOUND_INTEREST, List.of("1000", "2", "1.5")));

        assertThat(output).startsWith("CALCULATION FAILED · invalid_periods");
        assertThat(output).contains("COMPOUND_INTEREST was given '1.5' as the number of periods");
        assertThat(output).contains("whole number from 0 to 1200");
    }

    @Test
    @DisplayName("invalid_periods — beyond the 1200 ceiling")
    void tooManyPeriods() {
        String output = run(new CalculationStep(
                "grown", Operation.SIMPLE_INTEREST, List.of("1000", "2", "5000")));

        assertThat(output).startsWith("CALCULATION FAILED · invalid_periods");
        assertThat(output).contains("'5000' as the number of periods");
    }

    @Test
    @DisplayName("value_out_of_range — a runaway exponent, named rather than printed")
    void valueOutOfRange() {
        // 1000 x 2^1000 is around 10^304. The guard counts digits before the decimal
        // point rather than BigDecimal.precision(), which DECIMAL128 caps at 34 and
        // which therefore could never fire.
        String output = run(new CalculationStep(
                "runaway", Operation.COMPOUND_INTEREST, List.of("1000", "100", "1000")));

        assertThat(output).startsWith("CALCULATION FAILED · value_out_of_range");
        assertThat(output).contains("step: runaway");
        assertThat(output).contains("digits before the decimal point, and the limit is 200");
        assertThat(output).contains("a rate sent as 100 when 1 was meant");
    }

    @Test
    @DisplayName("value_out_of_range — a runaway in the other direction, which renders as leading zeros")
    void valueOutOfRangeBelowTheFloor() {
        // DECIMAL128 caps significant digits at 34 and says nothing about the exponent,
        // so a product of small operands stays a short number and grows a long scale:
        // six operands of scale 38 multiply out to scale 228. Nothing here is large,
        // and toPlainString still writes 228 characters of it — with 50 steps and 200
        // operands each, all inside the documented bounds, that reached 9.6 MB on the
        // success path, straight into the model's context.
        String tiny = "0." + "0".repeat(37) + "1";
        String output = run(new CalculationStep(
                "shrink", Operation.MULTIPLY, Collections.nCopies(6, tiny)));

        assertThat(output).startsWith("CALCULATION FAILED · value_out_of_range");
        assertThat(output).contains("step: shrink");
        assertThat(output).contains("228 digits after the decimal point, and the limit is 200");
        assertThat(output).contains("percent units");
    }

    @Test
    @DisplayName("a small result that still renders shortly is arithmetic, not a runaway")
    void aSmallResultInsideTheFloorIsStillAnAnswer() {
        // Five of the same operands land on scale 190. The gate is a bound on the
        // render, not a dislike of small numbers, so this one has to come back with an
        // answer — otherwise the fix for the line above has quietly become a fix that
        // refuses legitimate arithmetic.
        String tiny = "0." + "0".repeat(37) + "1";
        String output = run(new CalculationStep(
                "shrink", Operation.MULTIPLY, Collections.nCopies(5, tiny)));

        assertThat(output).doesNotContain("CALCULATION FAILED");
        assertThat(output).endsWith("ANSWER: shrink = 0.00 BRL");
    }

    @Test
    @DisplayName("internal_error — the catch of last resort really is reachable")
    void internalError() {
        // A list that answers size() and then throws on access. Nothing a model can
        // send looks like this; the point is that the branch exists and returns text.
        // Without it LangChain4j's default handler tells the model "this data source
        // is temporarily unavailable" — about a tool that has no data source.
        List<String> hostile = new AbstractList<>() {
            @Override
            public String get(int index) {
                throw new IllegalStateException("secret internal detail");
            }

            @Override
            public int size() {
                return 2;
            }
        };

        String output = run(new CalculationStep("boom", Operation.SUM, hostile));

        assertThat(output).startsWith("CALCULATION FAILED · internal_error");
        assertThat(output).contains("could not be completed");
        assertThat(output).contains("Do not do the arithmetic yourself");
        assertThat(output)
                .as("no class name, no stack frame and no exception message reaches the model")
                .doesNotContain("secret internal detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("io.github.rodrigorjsf");
    }

    @Test
    @DisplayName("every failure names itself first and offers a correction")
    void everyFailureIsShaped() {
        // A house-style check over the whole set rather than one more case: the model
        // parses the first line to decide whether it has an answer, and reads the
        // correction to write the retry.
        List<String> failures = List.of(
                calculator.calculate(List.of(), Currency.BRL, Rounding.HALF_EVEN),
                run(new CalculationStep("BAD", Operation.SUM, List.of("1"))),
                run(new CalculationStep("a", null, List.of("1"))),
                run(new CalculationStep("a", Operation.SUM, List.of("x"))),
                run(new CalculationStep("a", Operation.DIVIDE, List.of("1", "0"))));

        for (String failure : failures) {
            assertThat(failure).startsWith("CALCULATION FAILED · ");
            assertThat(failure).contains("\nproblem: ");
            assertThat(failure).contains("\ncorrection: ");
            assertThat(failure).doesNotContain("Exception");
            assertThat(failure).doesNotContain("\tat ");
        }
    }
}
