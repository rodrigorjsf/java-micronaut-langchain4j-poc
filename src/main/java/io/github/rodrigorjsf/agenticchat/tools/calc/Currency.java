package io.github.rodrigorjsf.agenticchat.tools.calc;

/**
 * The money a {@code calculate} call is denominated in, fixed for the whole
 * call.
 *
 * <p><b>One currency per call, by construction.</b> There is no per-step
 * currency, so a batch that mixes reais and dollars is unrepresentable rather
 * than validated. That is the stronger guarantee: a validation rule can be
 * written, shipped, and then quietly not run, whereas a field that does not
 * exist cannot be filled in wrong. A conversation that genuinely needs both
 * makes two calls, which is also the only shape in which the exchange rate — a
 * figure this tool refuses to invent — has to be fetched and passed in as an
 * operand.
 *
 * <p>Both members have scale 2, so the presentation scale is not yet a function
 * of the currency. It is modelled as one anyway, because the day a
 * zero-decimal currency is added the alternative is finding every hard-coded
 * {@code 2} in the formatter.
 */
public enum Currency {

    /** Brazilian real. */
    BRL(2),

    /** United States dollar. */
    USD(2);

    private final int scale;

    Currency(int scale) {
        this.scale = scale;
    }

    /** Decimal places used to present a figure in this currency. */
    public int scale() {
        return scale;
    }
}
