package io.github.rodrigorjsf.agenticchat.tools.calc;

/**
 * The closed set of things {@code calculate} can do, frozen at sixteen.
 *
 * <p>A Java enum reaches the model as a {@code JsonEnumSchema} listing exactly
 * these constants, so the set is not advice the model may ignore — an operation
 * outside it cannot be expressed. That is the whole reason this is an enum and
 * not a free-text {@code String op}: a typo becomes a schema violation the
 * provider rejects, rather than an {@code unknown_operation} the model discovers
 * one round trip later.
 *
 * <p><b>Arity lives here, next to the meaning.</b> Every operation states how
 * many operands it takes, because the alternative — a {@code switch} in the
 * executor that decides both what an operation means and how many operands it
 * wants — lets the two drift apart, and the drift is invisible until a model
 * sends the count the {@code switch} forgot to reject.
 *
 * <p><b>Percent operands are in percent units.</b> {@code 15} means 15%, never
 * {@code 0.15}. Halving that convention across operations would be the single
 * cheapest way to produce a wrong invoice, so it holds without exception here.
 *
 * <p>Deliberately absent: {@code ROUND} (every result already reports both the
 * exact and the rounded figure, so a rounding step would round something already
 * rounded), {@code POWER} ({@code COMPOUND_INTEREST} covers the only exponent
 * this domain needs), and currency conversion — a calculator that invented an FX
 * rate would be the exact failure this tool exists to remove. Rates come from
 * {@code get_fx_quote_brl} / {@code get_ptax_usd} in the {@code brazil-finance}
 * skill and are then passed in as operands.
 */
public enum Operation {

    /** Add every operand. */
    SUM(1, Arity.MANY),

    /** First operand minus each of the rest, left to right. */
    SUBTRACT(2, Arity.MANY),

    /** Multiply every operand. */
    MULTIPLY(1, Arity.MANY),

    /** First operand divided by each of the rest, left to right. */
    DIVIDE(2, Arity.MANY),

    /** {@code -x}. */
    NEGATE(1, 1, "[value]"),

    /** Arithmetic mean of every operand. */
    AVERAGE(1, Arity.MANY),

    /** Smallest operand. */
    MIN(1, Arity.MANY),

    /** Largest operand. */
    MAX(1, Arity.MANY),

    /**
     * {@code [percent, base]} to {@code base × percent ÷ 100}. "15% of 200" is
     * {@code ["15", "200"]} and yields 30.
     *
     * <p>The operand order is the mirror of {@link #ADD_PERCENT} on purpose —
     * see that constant.
     */
    PERCENT_OF(2, 2, "[percent, base]"),

    /**
     * {@code [base, percent]} to {@code base × (1 + percent ÷ 100)}. "200 plus
     * 15%" is {@code ["200", "15"]} and yields 230. Tax, markup, interest added.
     *
     * <p><b>The trap.</b> {@code PERCENT_OF} takes the percent first and
     * {@code ADD_PERCENT} takes the base first, because that is the order the two
     * phrases put them in — "15% of 200", "200 plus 15%". Swapping them is not a
     * crash: {@code PERCENT_OF ["200", "15"]} quietly computes 15 × 200 ÷ 100 =
     * 30 instead of 200 × 15 ÷ 100 = 30 — identical, because multiplication
     * commutes — while {@code ADD_PERCENT ["15", "200"]} computes 15 × 3 = 45
     * instead of 230. The commuting case is what makes this dangerous: the model
     * gets away with the swap on {@code PERCENT_OF} and therefore never learns
     * the order matters on {@code ADD_PERCENT}. The tool description and the
     * prompt appendix both state it explicitly for that reason.
     */
    ADD_PERCENT(2, 2, "[base, percent]"),

    /** {@code [base, percent]} to {@code base × (1 − percent ÷ 100)}. Discount. */
    SUBTRACT_PERCENT(2, 2, "[base, percent]"),

    /**
     * {@code [from, to]} to {@code (to − from) ÷ from × 100}. Returns percent
     * units, so a rise from 200 to 230 is {@code 15}, not {@code 0.15}.
     */
    PERCENT_CHANGE(2, 2, "[from, to]"),

    /**
     * {@code [part, whole]} to {@code part ÷ whole × 100}. "What percent of the
     * total is this line" — returns percent units.
     */
    RATIO_PERCENT(2, 2, "[part, whole]"),

    /**
     * {@code [principal, ratePercentPerPeriod, periods]} to the final amount
     * {@code P × (1 + i×n)}, interest not compounded.
     */
    SIMPLE_INTEREST(3, 3, "[principal, ratePercentPerPeriod, periods]"),

    /**
     * {@code [principal, ratePercentPerPeriod, periods]} to the final amount
     * {@code P × (1 + i)^n}.
     */
    COMPOUND_INTEREST(3, 3, "[principal, ratePercentPerPeriod, periods]"),

    /**
     * {@code [principal, ratePercentPerPeriod, periods]} to the payment due each
     * period on a Price (PMT) amortisation table:
     * {@code P × i × (1+i)^n ÷ ((1+i)^n − 1)}.
     *
     * <p>A rate of exactly zero degrades to {@code principal ÷ periods} rather
     * than dividing by a zero denominator — an interest-free instalment plan is a
     * real thing a user asks about, not an error.
     */
    INSTALLMENT_PAYMENT(3, 3, "[principal, ratePercentPerPeriod, periods]");

    /**
     * The "1..n" of the contract, parked on a nested type rather than on the enum
     * itself. An enum's own static fields are initialised <em>after</em> its
     * constants, so {@code SUM(1, MANY)} against a field declared below the
     * constants is an "illegal forward reference" at compile time — and enum
     * constants must come first, so there is nowhere above them to put it.
     *
     * <p>The sentinel is preferred to a nullable upper bound so the count check
     * stays one comparison with no branch on null. The real ceiling on operands is
     * the 200 bound the tool enforces, not this.
     */
    private static final class Arity {
        static final int MANY = Integer.MAX_VALUE;

        private Arity() {
        }
    }

    private final int minOperands;
    private final int maxOperands;
    private final String operandOrder;

    Operation(int minOperands, int maxOperands, String operandOrder) {
        this.minOperands = minOperands;
        this.maxOperands = maxOperands;
        this.operandOrder = operandOrder;
    }

    Operation(int minOperands, int maxOperands) {
        this(minOperands, maxOperands, "[value, value, ...]");
    }

    public int minOperands() {
        return minOperands;
    }

    public int maxOperands() {
        return maxOperands;
    }

    public boolean accepts(int operandCount) {
        return operandCount >= minOperands && operandCount <= maxOperands;
    }

    /**
     * The operand list written out with its slots named, for the correction line
     * of an error.
     *
     * <p>An arity on its own — "takes exactly 2 operands" — tells a model that got
     * the order wrong nothing at all, and the order is the likelier mistake of the
     * two. Naming the slots is what turns {@code wrong_operand_count} on
     * {@code ADD_PERCENT} into a call that comes back right rather than a call
     * that comes back with 45 instead of 230.
     */
    public String operandOrder() {
        return operandOrder;
    }

    /**
     * How the arity reads in an error message. The model has to be able to fix
     * the call from this sentence alone, so "2 or more" beats
     * "2..2147483647".
     */
    public String arityText() {
        if (maxOperands == Arity.MANY) {
            return minOperands + " or more operands";
        }
        if (minOperands == maxOperands) {
            return minOperands == 1 ? "exactly 1 operand" : "exactly " + minOperands + " operands";
        }
        return minOperands + " to " + maxOperands + " operands";
    }
}
