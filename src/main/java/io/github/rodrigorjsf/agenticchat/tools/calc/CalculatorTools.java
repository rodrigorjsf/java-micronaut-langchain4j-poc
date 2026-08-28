package io.github.rodrigorjsf.agenticchat.tools.calc;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.regex.Pattern;

/**
 * Exact arithmetic, so the model never has to do any.
 *
 * <p>A language model producing a number is producing the most plausible-looking
 * token sequence, and a wrong total looks exactly as plausible as a right one.
 * There is no log line for it, no confidence signal, and no way to tell the two
 * apart after the fact. This class exists so that every figure in an answer came
 * out of {@link BigDecimal} rather than out of a sampler.
 *
 * <h2>Why it is a static tool and not a skill</h2>
 *
 * <p>{@code CalculatorTools} is declared straight onto the assistant builder with
 * {@code .tools(calculator)}. It does not implement {@code SkillTools}, has no
 * {@code SKILL.md}, and is never disclosed by {@code activate_skill}. That is a
 * deliberate exception to this application's progressive-disclosure doctrine, and
 * it is the same exception the voice document already makes: a capability needed
 * on <em>some</em> turns is routed, a rule that applies to <em>every</em> turn is
 * not. A calculator behind {@code activate_skill} fails the way a voice document
 * behind {@code activate_skill} fails — the model does not notice that the turn
 * qualifies, does the sum in its head, and returns a fluent wrong number that no
 * log line records.
 *
 * <h2>The consequence of that, written down rather than engineered around</h2>
 *
 * <p>A statically declared tool does not pass through {@code ToolGuardProvider},
 * so this tool's result is not screened for indirect prompt injection and its
 * arguments are not screened for credential shapes. That is correct here, and it
 * is correct for a reason specific to this class rather than as a general
 * allowance: {@code calculate} makes no network call, reads no third-party data,
 * touches no file and no store, and returns only text this class formatted from
 * its own arithmetic. There is no channel through which an attacker could write
 * into the output. Add an upstream to this class — an FX rate, a tax table, a
 * currency service — and that argument collapses, so do not.
 * {@code LangfuseToolListener} is registered on the builder rather than on the
 * provider, so observability is unaffected by the same choice.
 *
 * <h2>Precision</h2>
 *
 * <p>Working precision is {@link MathContext#DECIMAL128}; the presented figure is
 * the currency's scale with the requested {@link Rounding}. <b>Intermediates are
 * never rounded to cents.</b> Rounding each step to two decimals and feeding that
 * forward accumulates error down an invoice, and the accumulated error is largest
 * exactly where invoices are longest. A step's stored value is the full-precision
 * value; only the displayed figure is rounded, and when the two differ the line
 * says so with {@code exact=}.
 *
 * <p>Every division passes {@code DECIMAL128} explicitly.
 * {@link BigDecimal#divide(BigDecimal)} with no {@code MathContext} throws
 * {@code ArithmeticException} on a non-terminating expansion, and one third is
 * the first case anybody hits. Every {@code BigDecimal} is built from a
 * {@code String}; {@code new BigDecimal(double)} appears nowhere, because
 * {@code new BigDecimal(0.1)} is 0.1000000000000000055511151231257827021181583404541015625.
 *
 * <h2>Nothing throws</h2>
 *
 * <p>{@link #calculate} has no path that lets a {@code RuntimeException} escape,
 * and no reachable path that produces anything else. The distinction is
 * deliberate and the catch is <em>not</em> widened to {@code Throwable}: catching
 * an {@code OutOfMemoryError} would mean allocating the error string at the
 * moment allocation is failing, which turns a crash into an unreliable one, and
 * {@code catch (Throwable)} would swallow a {@code LinkageError} or a
 * {@code StackOverflowError} into a permanent silent {@code internal_error}. An
 * {@code OutOfMemoryError} was in fact reachable here once — see
 * {@link #numeralCorrection} — and it was closed by removing the unbounded
 * allocation rather than by catching its consequence.
 *
 * <p>The catch of last resort returns {@code CALCULATION FAILED · internal_error}
 * because the framework's {@code toolExecutionErrorHandler} would otherwise tell
 * the model "this data source is temporarily unavailable" — for a tool that has
 * no data source, which is a sentence the model would repeat to the user and a
 * lie it would have no way to detect.
 */
@Singleton
public class CalculatorTools {

    /**
     * 34 significant digits, half-even. Wide enough that a hundred-step invoice
     * never notices it, narrow enough that a result is still a finite string.
     */
    private static final MathContext WORKING = MathContext.DECIMAL128;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * The whole numeral grammar: optional minus, digits, optionally a point and
     * more digits. Deliberately strict. {@code 1.234} is one thousand two hundred
     * and thirty-four under one convention and one-point-two-three-four under the
     * other, and a money tool that guesses between them is worse than a tool that
     * asks — so nothing here is normalised, stripped or interpreted before the
     * match.
     */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private static final Pattern STEP_ID = Pattern.compile("[a-z0-9_]{1,32}");

    /**
     * Everything a rejected operand may contain <em>besides</em> numerals and still
     * earn a derived correction: whitespace and the marks money is written with.
     * Anything else — a letter, a slash, a word — and the tool states the grammar
     * and stops, because a correction it had to invent is the guess the strict
     * grammar exists to refuse.
     */
    private static final Pattern DECORATION = Pattern.compile("[\\s$%R€£¥]*");

    private static final int MAX_STEPS = 50;
    private static final int MAX_OPERANDS = 200;
    private static final int MAX_OPERAND_CHARS = 40;
    private static final int MAX_PERIODS = 1200;

    /**
     * The magnitude ceiling, counted in digits before the decimal point.
     *
     * <p>It is <em>not</em> {@code BigDecimal.precision()}, although the contract
     * names that: under {@code DECIMAL128} every stored value has at most 34
     * significant digits, so a check on {@code precision()} could never fire and
     * would be a guard that measures nothing. What actually runs away is the
     * exponent — {@code COMPOUND_INTEREST} at 100% for a thousand periods is
     * around 10^304 — and that shows up in {@code precision() - scale()}.
     */
    private static final int MAX_INTEGER_DIGITS = 200;

    /**
     * The same ceiling at the other end, counted in digits after the point.
     *
     * <p>This gate is about the <em>render</em>, not about the number. A very small
     * number is harmless as a number; it is 34 significant digits like any other,
     * and it presents as {@code 0.00}. What is not harmless is
     * {@link BigDecimal#toPlainString()}, which writes every leading zero, and
     * {@code DECIMAL128} bounds significant digits while placing no bound at all on
     * the exponent. So a scale grows where a precision cannot: multiplying operands
     * of scale 38 adds their scales, and six of them already render 228 characters
     * for a figure the model reads as zero.
     *
     * <p>That is not a corner. Fifty steps of two hundred such operands — every one
     * of them inside the bounds in the contract, on the success path, with no error
     * anywhere — produced a 9.6 MB result, and a statically declared tool has no
     * {@code ToolGuardProvider} in front of it to clamp that before the model reads
     * it. Both bounds are enforced in {@link #inRange}, which every step's value
     * passes through before it is stored or rendered, so the two render sites
     * ({@link #line} and {@link #present}) are both downstream of it. Move a render
     * upstream of that call and the bound becomes decoration.
     */
    private static final int MAX_FRACTION_DIGITS = 200;

    /** Column widths of the result table. See {@link #line}. */
    private static final int ID_COLUMN = 12;
    private static final int OPERATION_COLUMN = 17;

    @Tool("""
            Do arithmetic exactly. Send a list of steps that run in order and get back every
            step's value plus the final answer. Use this for EVERY calculation the answer
            depends on — totals, subtotals, discounts, taxes, tips, splitting a bill,
            percentages, percentage change, simple and compound interest, instalments — and
            use it for the easy-looking ones too. Never work a number out yourself.

            A step is {"id": "...", "operation": "...", "operands": [...]}. A later step uses
            an earlier step's result by writing # in front of that step's id, for example
            "#subtotal". The value of the LAST step is the answer.

            Operands are plain numbers: digits, an optional leading minus, and an optional
            decimal POINT. No thousands separator, no currency symbol, no percent sign, no
            exponent. Send "1234.56", never "1.234,56" or "1,234.56" or "R$ 1234,56" or
            "12%". A percentage goes in as percent units: 15 means 15 percent, not 0.15.

            OPERAND ORDER MATTERS, and the two commonest percent operations are mirrored on
            purpose because that is how the two phrases are spoken:
            PERCENT_OF takes [percent, base] — "15% of 200" is ["15","200"] and gives 30.
            ADD_PERCENT takes [base, percent] — "200 plus 15%" is ["200","15"] and gives 230.
            Sending ADD_PERCENT as ["15","200"] returns 45 instead of 230, and nothing reports
            an error, so check this order every time.

            Worked example — "1350 reais, 5% off, then 8% tax", with currency BRL:
            [{"id":"subtotal","operation":"SUM","operands":["1350"]},
             {"id":"discount","operation":"SUBTRACT_PERCENT","operands":["#subtotal","5"]},
             {"id":"tax","operation":"ADD_PERCENT","operands":["#discount","8"]}]
            comes back with one line per step and ANSWER: tax = 1385.10 BRL.

            The reply is plain text. A bad call comes back starting with CALCULATION FAILED,
            naming the step, what was wrong and how to correct it: fix the call and send it
            again, and never fall back to doing the arithmetic yourself.""")
    public String calculate(
            @P("""
                    The steps, in the order they run. One to 50 of them. Each has an id, an
                    operation and its operands; a step may use an earlier step's result by
                    referring to it as #thatId. The last step is the answer.""")
            List<CalculationStep> steps,
            @P("""
                    The currency the figures are in, BRL or USD. It applies to the whole call,
                    so one call never mixes currencies. Both are shown with 2 decimal places.""")
            Currency currency,
            @P(value = """
                    How to round the figures that are shown. HALF_EVEN (the default) rounds a
                    tie to the even digit and is the Brazilian standard for arithmetic;
                    HALF_UP rounds a tie away from zero and is what most billing systems use
                    for a final amount. UP, DOWN, CEILING and FLOOR never look at ties.
                    Only the shown figures are rounded — the arithmetic itself always runs at
                    full precision.""",
                    required = false, defaultValue = "HALF_EVEN")
            Rounding rounding) {
        try {
            return run(steps, currency, rounding);
        } catch (CalculationFailure failure) {
            return failure.text();
        } catch (RuntimeException unexpected) {
            // The catch of last resort. It is deliberately blind: whatever a future
            // edit breaks in here, the model must read a sentence that says the sum
            // did not run, not a framework sentence about an unavailable data source.
            // No class name and no stack trace — neither means anything to the model,
            // and both are noise in the next prompt.
            return failure("internal_error", null,
                    "the calculation could not be completed.",
                    "resend the call with simpler steps. Do not do the arithmetic yourself; "
                            + "if it fails again, tell the user the calculation could not be run.")
                    .text();
        }
    }

    // ------------------------------------------------------------------
    // The single forward pass
    // ------------------------------------------------------------------

    /**
     * One pass, in array order, with each step's value published to a map the
     * later steps read.
     *
     * <p>That ordering is the whole reference model. {@code #id} resolves against
     * a step that has <em>already run</em>, so a forward reference and a self
     * reference are both simply "not in the map yet" and share one check. There is
     * no dependency graph, no topological sort and no cycle detection, because a
     * cycle cannot be written down — machinery to rule out an unrepresentable
     * state is machinery that can only ever be wrong.
     */
    private String run(List<CalculationStep> steps, Currency currency, Rounding rounding) {
        // The two nulls are answered differently, and the difference is the whole
        // point. `rounding` is declared optional with defaultValue = "HALF_EVEN", so
        // the framework substitutes at the tool door and a null here can only come
        // from a direct Java caller — every test in this package — for which the
        // declared default is the right answer.
        //
        // `currency` is declared required with no default, so a null is a protocol
        // violation and defaulting it would convert that into a silent assumption.
        // It is reachable: P's own javadoc records that in 1.x an object parameter
        // marked required is NOT validated and null is passed to the method anyway.
        // The failure it would cause is the one this whole class exists to remove —
        // a dollar invoice answered "ANSWER: 1385.10 BRL", where the header naming
        // BRL is not a warning but a confirmation of something the model never sent.
        // A tool that bypasses ToolGuardProvider on the argument that it never lies
        // does not get to guess the unit of the money it is counting.
        Rounding mode = rounding == null ? Rounding.HALF_EVEN : rounding;
        if (currency == null) {
            throw failure("missing_currency", null,
                    "no currency was sent, and the currency is required.",
                    "resend the same steps with \"currency\":\"BRL\" or \"currency\":\"USD\". "
                            + "If the user has not said which, ask them — do not assume one.");
        }
        Currency money = currency;

        if (steps == null || steps.isEmpty()) {
            throw failure("empty_program", null,
                    "no steps were sent, so there is nothing to calculate.",
                    "send at least one step, for example "
                            + "[{\"id\":\"total\",\"operation\":\"SUM\",\"operands\":[\"10\",\"20\"]}].");
        }
        if (steps.size() > MAX_STEPS) {
            throw failure("too_many_steps", null,
                    steps.size() + " steps were sent and the limit is " + MAX_STEPS + ".",
                    "split the work across more than one call, or combine the steps that add "
                            + "the same kind of figure into a single SUM.");
        }

        Map<String, BigDecimal> earlier = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        String answerId = null;
        BigDecimal answerValue = null;

        for (int position = 0; position < steps.size(); position++) {
            CalculationStep step = steps.get(position);
            if (step == null) {
                throw failure("invalid_step_id", null,
                        "step number " + (position + 1) + " is empty.",
                        "every step needs an id, an operation and a list of operands.");
            }
            String id = validId(step.id(), position);
            if (earlier.containsKey(id)) {
                throw failure("duplicate_step_id", id,
                        "the id '" + id + "' is used by more than one step, so a #" + id
                                + " reference would be ambiguous.",
                        "give every step its own id, for example '" + id + "_2'.");
            }
            Operation operation = step.operation();
            if (operation == null) {
                throw failure("unknown_operation", id,
                        "this step did not name an operation.",
                        "set operation to one of: " + names() + ".");
            }
            List<BigDecimal> operands = operandsOf(step, id, operation, earlier);

            BigDecimal value = inRange(apply(operation, operands, id, mode), id);
            earlier.put(id, value);
            body.append(line(id, operation, value, money, mode)).append('\n');
            answerId = id;
            answerValue = value;
        }

        return header(money, mode) + '\n' + body
                + "ANSWER: " + answerId + " = " + present(answerValue, money, mode) + " " + money;
    }

    /**
     * Anything the model sent, cut to a length that is safe to hand back to it.
     *
     * <p>Every error message here quotes the argument it rejected, because an
     * error naming nothing is an error the model cannot act on. But the argument
     * is model-supplied and unbounded, and the message is the next thing the model
     * reads — so quoting it whole makes the tool's own error the payload. This is
     * the third instance of that shape found in this class: an operand carrying a
     * large exponent, a result rendered at a huge scale, and now the two branches
     * below, which run <em>before</em> any length check could have fired.
     *
     * <p>A reference is checked against the map before the operand-length guard,
     * and an id is checked against {@code STEP_ID} before anything else — so the
     * 40-character and 32-character bounds elsewhere in the class do not cover
     * either of them. {@code STEP_ID} bounds the ids a call <em>defines</em>, never
     * the string it <em>references</em>.
     */
    private static String clip(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= MAX_OPERAND_CHARS
                ? raw
                : raw.substring(0, MAX_OPERAND_CHARS) + "… (" + raw.length() + " characters)";
    }

    private static String validId(String raw, int position) {
        String id = raw == null ? "" : raw.strip();
        if (!STEP_ID.matcher(id).matches()) {
            throw failure("invalid_step_id", null,
                    "step number " + (position + 1) + " has the id '" + clip(raw)
                            + "', which is not usable.",
                    "use 1 to 32 characters, lower-case letters, digits and underscores only — "
                            + "for example 'subtotal', 'icms' or 'total_com_frete'.");
        }
        return id;
    }

    private static List<BigDecimal> operandsOf(
            CalculationStep step, String id, Operation operation, Map<String, BigDecimal> earlier) {
        List<String> raw = step.operands();
        if (raw == null || raw.isEmpty()) {
            throw failure("no_operands", id,
                    "this step has no operands.",
                    operation + " takes " + operation.arityText() + ", in the order "
                            + operation.operandOrder() + ".");
        }
        if (raw.size() > MAX_OPERANDS) {
            throw failure("too_many_operands", id,
                    raw.size() + " operands were sent and the limit per step is " + MAX_OPERANDS + ".",
                    "add them in batches — sum the first " + MAX_OPERANDS + " in one step, the rest "
                            + "in another, then sum the two step results.");
        }
        if (!operation.accepts(raw.size())) {
            throw failure("wrong_operand_count", id,
                    operation + " was sent " + raw.size() + (raw.size() == 1 ? " operand" : " operands")
                            + " and it takes " + operation.arityText() + ".",
                    "resend this step with the operands in the order " + operation.operandOrder() + ".");
        }
        List<BigDecimal> values = new ArrayList<>(raw.size());
        for (String operand : raw) {
            values.add(resolve(operand, id, earlier));
        }
        return values;
    }

    /**
     * An operand is a reference when it starts with {@code #} and a number
     * otherwise. The two cannot collide: the numeral grammar has no place for a
     * {@code #}, so no valid number is ever read as a reference and no reference
     * is ever read as a number.
     */
    private static BigDecimal resolve(String raw, String stepId, Map<String, BigDecimal> earlier) {
        // Whitespace is stripped and nothing else is. Leading spaces are not
        // ambiguous, so rejecting " 10" would be a false failure; a comma IS
        // ambiguous, so it is never touched.
        String operand = raw == null ? "" : raw.strip();

        if (operand.startsWith("#")) {
            String reference = operand.substring(1);
            BigDecimal value = earlier.get(reference);
            if (value == null) {
                throw failure("unknown_reference", stepId,
                        "'" + clip(operand) + "' does not name any step that has already run.",
                        "a # reference reaches only a step that appears BEFORE this one in the "
                                + "list. Available here: " + known(earlier) + ".");
            }
            return value;
        }
        if (operand.length() > MAX_OPERAND_CHARS) {
            throw failure("invalid_number", stepId,
                    "an operand is " + operand.length() + " characters long and the limit is "
                            + MAX_OPERAND_CHARS + ".",
                    "send the figure itself, with no padding, no separators and no units.");
        }
        if (!NUMBER.matcher(operand).matches()) {
            throw failure("invalid_number", stepId,
                    "the operand '" + raw + "' is not a number this tool accepts.",
                    numeralCorrection(operand));
        }
        return new BigDecimal(operand);
    }

    // ------------------------------------------------------------------
    // Arithmetic
    // ------------------------------------------------------------------

    private static BigDecimal apply(Operation operation, List<BigDecimal> v, String stepId,
                                    Rounding mode) {
        return switch (operation) {
            case SUM -> v.stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, WORKING));
            case SUBTRACT -> leftToRight(v, (a, b) -> a.subtract(b, WORKING));
            case MULTIPLY -> v.stream().reduce(BigDecimal.ONE, (a, b) -> a.multiply(b, WORKING));
            case DIVIDE -> leftToRight(v, (a, b) ->
                    quotient(a, b, stepId, "a divisor in this DIVIDE step is zero.",
                            "check the operands after the first one; none of them may be zero."));
            case NEGATE -> v.getFirst().negate();
            // The one operation that changes a value rather than computing one. It
            // takes the call's rounding mode rather than a mode of its own: two
            // rounding modes in one result is a number nobody can reproduce, and the
            // header names only one of them.
            case ROUND -> round(v, mode, stepId);
            // The operand-count guard has already made the list non-empty, so the
            // divisor here cannot be zero. §5 of the contract routes "AVERAGE with no
            // operands" to division_by_zero; that path is unreachable behind the 1..200
            // bound, and no_operands names the real problem better anyway.
            case AVERAGE -> v.stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, WORKING))
                    .divide(BigDecimal.valueOf(v.size()), WORKING);
            case MIN -> v.stream().reduce(BigDecimal::min).orElseThrow();
            case MAX -> v.stream().reduce(BigDecimal::max).orElseThrow();
            case PERCENT_OF -> v.get(1).multiply(v.getFirst(), WORKING).divide(HUNDRED, WORKING);
            case ADD_PERCENT -> v.getFirst().multiply(
                    BigDecimal.ONE.add(v.get(1).divide(HUNDRED, WORKING), WORKING), WORKING);
            case SUBTRACT_PERCENT -> v.getFirst().multiply(
                    BigDecimal.ONE.subtract(v.get(1).divide(HUNDRED, WORKING), WORKING), WORKING);
            case PERCENT_CHANGE -> quotient(
                    v.get(1).subtract(v.getFirst(), WORKING), v.getFirst(), stepId,
                    "PERCENT_CHANGE divides by the 'from' value, and it is zero.",
                    "a change from zero has no percentage. Report the absolute difference "
                            + "instead, or use RATIO_PERCENT if you wanted a share of a total.")
                    .multiply(HUNDRED, WORKING);
            case RATIO_PERCENT -> quotient(
                    v.getFirst(), v.get(1), stepId,
                    "RATIO_PERCENT divides by the 'whole', and it is zero.",
                    "send the total as the second operand; a share of zero is undefined.")
                    .multiply(HUNDRED, WORKING);
            case SIMPLE_INTEREST -> simpleInterest(v, stepId);
            case COMPOUND_INTEREST -> compoundInterest(v, stepId);
            case INSTALLMENT_PAYMENT -> installmentPayment(v, stepId);
        };
    }

    /** {@code P × (1 + i×n)} — interest that is not put back to work. */
    /**
     * {@code [value, decimalPlaces]}, rounded with the call's mode.
     *
     * <p>{@code decimalPlaces} is an operand like any other, so it arrives as a
     * {@code BigDecimal} and may be anything the grammar accepts. It is bounded
     * here rather than trusted: {@code setScale} takes an {@code int}, and a scale
     * of two hundred million is the render defect this class has already been bitten
     * by twice. The ceiling is the same {@link #MAX_FRACTION_DIGITS} every result
     * passes, so a value this operation could produce is a value {@code inRange}
     * would accept.
     */
    private static BigDecimal round(List<BigDecimal> v, Rounding mode, String stepId) {
        BigDecimal places = v.get(1);
        if (places.stripTrailingZeros().scale() > 0) {
            throw failure("invalid_number", stepId,
                    "ROUND takes a whole number of decimal places, and '"
                            + places.toPlainString() + "' is not one.",
                    "use 2 for centavos, 0 for whole units.");
        }
        if (places.compareTo(BigDecimal.ZERO) < 0
                || places.compareTo(BigDecimal.valueOf(MAX_FRACTION_DIGITS)) > 0) {
            throw failure("value_out_of_range", stepId,
                    "ROUND was asked for " + places.toPlainString() + " decimal places, and the "
                            + "range is 0 to " + MAX_FRACTION_DIGITS + ".",
                    "use 2 for centavos, 0 for whole units.");
        }
        return v.getFirst().setScale(places.intValueExact(), mode.mode());
    }

    private static BigDecimal simpleInterest(List<BigDecimal> v, String stepId) {
        BigDecimal principal = v.getFirst();
        BigDecimal rate = v.get(1).divide(HUNDRED, WORKING);
        int periods = periods(v.get(2), stepId, Operation.SIMPLE_INTEREST);
        return principal.multiply(
                BigDecimal.ONE.add(rate.multiply(BigDecimal.valueOf(periods), WORKING), WORKING), WORKING);
    }

    /** {@code P × (1 + i)^n}. */
    private static BigDecimal compoundInterest(List<BigDecimal> v, String stepId) {
        BigDecimal principal = v.getFirst();
        BigDecimal rate = v.get(1).divide(HUNDRED, WORKING);
        int periods = periods(v.get(2), stepId, Operation.COMPOUND_INTEREST);
        return principal.multiply(BigDecimal.ONE.add(rate, WORKING).pow(periods, WORKING), WORKING);
    }

    /**
     * The Price table's PMT: {@code P × i × (1+i)^n ÷ ((1+i)^n − 1)}.
     *
     * <p>Two degenerate cases are handled rather than divided through. A rate of
     * exactly zero collapses the formula to {@code 0 ÷ 0}, and an interest-free
     * instalment plan is an ordinary thing to ask about, so it becomes
     * {@code principal ÷ periods}. A rate that makes {@code (1+i)^n} land back on
     * 1 — {@code i = -2} with an even period count — leaves a zero denominator
     * with no sensible reading at all, so that one is named and returned.
     */
    private static BigDecimal installmentPayment(List<BigDecimal> v, String stepId) {
        BigDecimal principal = v.getFirst();
        BigDecimal rate = v.get(1).divide(HUNDRED, WORKING);
        int periods = periods(v.get(2), stepId, Operation.INSTALLMENT_PAYMENT);
        if (periods == 0) {
            throw failure("division_by_zero", stepId,
                    "INSTALLMENT_PAYMENT was asked for 0 periods, and a payment per period "
                            + "divides by the number of periods.",
                    "send the number of instalments, which must be at least 1.");
        }
        if (rate.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(periods), WORKING);
        }
        BigDecimal growth = BigDecimal.ONE.add(rate, WORKING).pow(periods, WORKING);
        BigDecimal denominator = growth.subtract(BigDecimal.ONE, WORKING);
        return quotient(principal.multiply(rate, WORKING).multiply(growth, WORKING), denominator, stepId,
                "this rate and period count leave the instalment formula dividing by zero.",
                "check the rate: it is a percentage per period, so 2 means 2% and not 200%.");
    }

    /**
     * @param raw the third operand of a finance operation, which must be a whole
     *            number of periods
     */
    private static int periods(BigDecimal raw, String stepId, Operation operation) {
        BigDecimal whole = raw.stripTrailingZeros();
        int periods;
        try {
            // stripTrailingZeros turns 600 into 6E+2, which intValueExact still reads
            // as 600; it turns 1.50 into 1.5, which it refuses. That is the check.
            periods = whole.intValueExact();
        } catch (ArithmeticException notWhole) {
            throw invalidPeriods(raw, stepId, operation);
        }
        if (periods < 0 || periods > MAX_PERIODS) {
            throw invalidPeriods(raw, stepId, operation);
        }
        return periods;
    }

    private static CalculationFailure invalidPeriods(BigDecimal raw, String stepId, Operation operation) {
        return failure("invalid_periods", stepId,
                operation + " was given '" + raw.toPlainString() + "' as the number of periods.",
                "periods is a whole number from 0 to " + MAX_PERIODS + " — how many months, years "
                        + "or instalments the rate is applied over. Convert years to months yourself "
                        + "in an earlier MULTIPLY step if you need to.");
    }

    private static BigDecimal leftToRight(
            List<BigDecimal> values, BinaryOperator<BigDecimal> fold) {
        BigDecimal accumulator = values.getFirst();
        for (int i = 1; i < values.size(); i++) {
            accumulator = fold.apply(accumulator, values.get(i));
        }
        return accumulator;
    }

    /** Every division in this class goes through here, so none can forget the context. */
    private static BigDecimal quotient(
            BigDecimal dividend, BigDecimal divisor, String stepId, String problem, String correction) {
        if (divisor.signum() == 0) {
            throw failure("division_by_zero", stepId, problem, correction);
        }
        return dividend.divide(divisor, WORKING);
    }

    /**
     * Both ends of the magnitude, because both ends are rendered in full.
     *
     * <p>{@code scale()} is read straight rather than derived: it <em>is</em> the
     * count of digits after the point, so the check that bounds the render is the
     * same field the render reads, with no second quantity that could disagree with
     * it after an edit.
     */
    private static BigDecimal inRange(BigDecimal value, String stepId) {
        int integerDigits = value.precision() - value.scale();
        if (integerDigits > MAX_INTEGER_DIGITS) {
            throw failure("value_out_of_range", stepId,
                    "this step produced a number with about " + integerDigits + " digits before the "
                            + "decimal point, and the limit is " + MAX_INTEGER_DIGITS + ".",
                    "check the operands — a result this size is almost always a rate sent as 100 "
                            + "when 1 was meant, or a period count far larger than intended.");
        }
        if (value.scale() > MAX_FRACTION_DIGITS) {
            throw failure("value_out_of_range", stepId,
                    "this step produced a number needing " + value.scale() + " digits after the "
                            + "decimal point, and the limit is " + MAX_FRACTION_DIGITS + ". It is far "
                            + "below one cent, so it presents as 0.00 while writing out every zero.",
                    "check the operands — a result this small is almost always a percentage sent as "
                            + "0.15 when this tool takes percent units and wanted 15, or a chain of "
                            + "multiplications where a division was meant.");
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static String header(Currency money, Rounding mode) {
        // Stated on every successful result, because the model has to be able to say
        // out loud which rounding produced the figure it is quoting. A default nobody
        // can see is a default nobody can correct.
        return "CALCULATION · currency=" + money + " · rounding=" + mode + " · scale=" + money.scale();
    }

    /**
     * One step, as a fixed-width row: id in 12 columns, operation in 17, then
     * {@code = } and the value.
     *
     * <p>The widths are constants rather than the longest entry in this particular
     * call. A width computed per call renders the same step differently depending
     * on its neighbours, which makes the output impossible to assert on and
     * changes what the model reads for reasons that have nothing to do with the
     * arithmetic. An entry longer than its column simply takes one space.
     */
    private static String line(String id, Operation operation, BigDecimal value,
                               Currency money, Rounding mode) {
        BigDecimal shown = value.setScale(money.scale(), mode.mode());
        StringBuilder row = new StringBuilder()
                .append(pad(id, ID_COLUMN))
                .append(pad(operation.name(), OPERATION_COLUMN))
                .append("= ")
                .append(shown.toPlainString());
        // Only when it adds something. An invoice of whole cents that repeated
        // "exact=1350.00" on every line would train the model to skip the field on
        // the one line where it matters.
        if (shown.compareTo(value) != 0) {
            row.append(" exact=").append(value.toPlainString());
        }
        return row.toString();
    }

    /**
     * {@code toPlainString}, never {@code toString}: once a scale goes negative —
     * which {@code pow} and a large {@code multiply} both produce —
     * {@code toString} emits scientific notation, and {@code 1.0E+2} where the
     * answer should read {@code 100.00} is a wrong-looking total with correct
     * arithmetic behind it.
     */
    private static String present(BigDecimal value, Currency money, Rounding mode) {
        return value.setScale(money.scale(), mode.mode()).toPlainString();
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }

    private static String names() {
        return String.join(", ", Arrays.stream(Operation.values()).map(Enum::name).toList());
    }

    private static String known(Map<String, BigDecimal> earlier) {
        return earlier.isEmpty() ? "nothing yet, this is the first step" : "#" + String.join(", #", earlier.keySet());
    }

    /**
     * What to write under an operand the grammar rejected.
     *
     * <p>A correction is only offered when it can be <em>derived</em> rather than
     * guessed. Both separators present fixes the reading — the last one is the
     * decimal point — and a stray {@code R$} or {@code %} carries no ambiguity at
     * all. A lone comma genuinely does: {@code 1,234} is 1.234 in pt-BR and 1234
     * in en-US, so both readings are offered and neither is chosen. Guessing here
     * would be the same failure the strict grammar exists to prevent, one layer
     * further in.
     *
     * <p>A derived correction is also <em>measured before it is written</em>. The
     * operand-length check upstream of this method bounds the operand, not its
     * expansion, and an exponent puts no relation between the two: {@code 1e10000000}
     * is eleven characters and expands to ten million, which arrived as a
     * ten-megabyte error message, and {@code 1e999999999} exhausted the heap on the
     * way — as an {@code OutOfMemoryError}, which is not a {@code RuntimeException}
     * and so walked straight through the catch of last resort. The length is
     * therefore computed from {@code precision()} and {@code scale()}, which are
     * field reads, and never by building the string and asking how long it turned
     * out to be. The bound is {@link #MAX_OPERAND_CHARS}, because a correction the
     * tool would reject if the model sent it back is not a correction.
     */
    private static String numeralCorrection(String operand) {
        String grammar = "operands take digits, an optional leading minus and an optional decimal "
                + "POINT — no thousands separator, no currency symbol, no percent sign, no exponent.";

        // Exponent form and a leading plus first, and by asking BigDecimal rather
        // than by editing the string. Character surgery here would read "1e3" as
        // "13" — the 'e' is not decoration, it is three orders of magnitude.
        try {
            BigDecimal expanded = new BigDecimal(operand);
            long characters = plainStringLength(expanded);
            if (characters > MAX_OPERAND_CHARS) {
                // Returns rather than falling through, because the fall-through lands on
                // the separator logic, which finds an 'e' among the decoration, gives up,
                // and answers with the bare grammar. The model would then have been told
                // only that exponents are refused, and would retry with a slightly
                // smaller one. It has to be told the magnitude is the problem too.
                return grammar + " Written out in full '" + operand + "' is " + characters
                        + " characters long, and an operand may be at most " + MAX_OPERAND_CHARS
                        + ", so this figure cannot be sent at all. Check the exponent; if it is "
                        + "what was meant, the number is outside what this calculator handles and "
                        + "the answer has to say so.";
            }
            return grammar + " Write '" + operand + "' as '" + expanded.toPlainString() + "'.";
        } catch (NumberFormatException notANumberAtAll) {
            // Expected for every separator case below; fall through.
        }

        String digits = operand.replaceAll("[0-9,.\\-]", "");
        if (!DECORATION.matcher(digits).matches()) {
            // Something is in there that is neither a numeral nor a currency mark, so
            // no correction can be derived without inventing one.
            return grammar;
        }
        String bare = operand.replaceAll("[^0-9,.\\-]", "");
        int lastComma = bare.lastIndexOf(',');
        int lastDot = bare.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            // Both separators present settles the reading: the last one is the decimal
            // point, whichever convention wrote it. Nothing is guessed.
            char grouping = lastComma > lastDot ? '.' : ',';
            String fixed = bare.replace(String.valueOf(grouping), "").replace(',', '.');
            if (NUMBER.matcher(fixed).matches()) {
                return grammar + " Write '" + operand + "' as '" + fixed + "'.";
            }
        } else if (lastComma >= 0) {
            String asDecimal = bare.replace(',', '.');
            String asGrouping = bare.replace(",", "");
            if (NUMBER.matcher(asDecimal).matches() && NUMBER.matcher(asGrouping).matches()) {
                return grammar + " A comma is never read here: send '" + asDecimal + "' if it was a "
                        + "decimal comma, or '" + asGrouping + "' if it separated thousands.";
            }
        } else if (NUMBER.matcher(bare).matches()) {
            return grammar + " Write '" + operand + "' as '" + bare + "'.";
        }
        return grammar;
    }

    /**
     * How long {@link BigDecimal#toPlainString()} would be, without calling it.
     *
     * <p>{@code precision()} and {@code scale()} are field reads and neither depends
     * on the exponent, so this answers for {@code 1e999999999} as cheaply as for
     * {@code 1.5}. Measuring the string by building it first would be the bug rather
     * than the check: the allocation <em>is</em> what runs out of memory.
     *
     * <p>The cases are the shapes {@code toPlainString} writes — digits followed by
     * trailing zeros when the scale is not positive, digits with a point inside them
     * when there are more digits than decimal places, and {@code 0.} followed by
     * leading zeros when there are not. Zero at a non-positive scale is the one that
     * does not follow the pattern: {@code toPlainString} short-circuits it to
     * {@code "0"} rather than writing the five hundred trailing zeros that
     * {@code 0e500} nominally carries, and without that branch the model is told the
     * number zero is too large to send.
     *
     * <p>The arithmetic is in {@code long} because a scale reaches
     * {@link Integer#MIN_VALUE}: {@code 1e2147483647} measures 2 147 483 648
     * characters, which in {@code int} is a negative length and a guard that never
     * fires on the one operand that most needs it.
     */
    private static long plainStringLength(BigDecimal value) {
        long digits = value.precision();
        long scale = value.scale();
        long sign = value.signum() < 0 ? 1 : 0;
        if (scale <= 0) {
            return value.signum() == 0 ? 1 : sign + digits - scale;
        }
        return sign + (digits > scale ? digits + 1 : scale + 2);
    }

    // ------------------------------------------------------------------

    private static CalculationFailure failure(
            String name, String stepId, String problem, String correction) {
        StringBuilder text = new StringBuilder("CALCULATION FAILED · ").append(name);
        if (stepId != null) {
            text.append("\nstep: ").append(stepId);
        }
        text.append("\nproblem: ").append(problem);
        text.append("\ncorrection: ").append(correction);
        return new CalculationFailure(text.toString());
    }

    /**
     * Control flow inside this class, and nowhere else.
     *
     * <p>The alternative — returning an {@code Either} from every helper — threads
     * a result carrier through sixteen operations, three nesting levels and every
     * arithmetic expression, and the arithmetic is the part a reader has to be
     * able to check by eye. An exception keeps the formulas readable.
     *
     * <p>It is caught in {@link #calculate}, one frame above every throw site, and
     * turned into text. It never leaves this class, so the "nothing throws"
     * guarantee holds at the only boundary where it means anything. The stack
     * trace is suppressed because nothing ever reads it: the message is the whole
     * payload.
     */
    private static final class CalculationFailure extends RuntimeException {

        private final String text;

        private CalculationFailure(String text) {
            super(null, null, false, false);
            this.text = text;
        }

        private String text() {
            return text;
        }
    }
}
