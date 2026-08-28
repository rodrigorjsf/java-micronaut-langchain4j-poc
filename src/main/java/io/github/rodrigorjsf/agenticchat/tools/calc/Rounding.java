package io.github.rodrigorjsf.agenticchat.tools.calc;

import java.math.RoundingMode;

/**
 * How a figure is rounded when it is presented, mirroring
 * {@link RoundingMode} minus one value.
 *
 * <p><b>Why this is not {@code java.math.RoundingMode} directly.</b> A Java enum
 * parameter reaches the model as a {@code JsonEnumSchema} listing every one of
 * its constants, so using {@code RoundingMode} would advertise
 * {@code UNNECESSARY} as a legal choice. {@code UNNECESSARY} is not a rounding
 * mode; it is an assertion that no rounding is needed, and it throws
 * {@code ArithmeticException} the first time a result does not land exactly on
 * two decimals. A third of a bill divided three ways is that first time. The
 * enum is retyped here so the value the model cannot survive is not in the
 * vocabulary the model is handed — the same argument as {@link Currency}: an
 * option that does not exist cannot be picked.
 *
 * <p><b>The default is {@code HALF_EVEN}, and that is a stated assumption rather
 * than a proved fact.</b> ABNT NBR 5891 specifies round-half-to-even and
 * {@code MathContext.DECIMAL128} uses it, while Brazilian billing and tax
 * systems commonly settle a final monetary amount with {@code HALF_UP}. This
 * class does not break that tie. What makes either choice safe is that every
 * successful result prints the mode and the scale actually applied, so a wrong
 * default is one visible parameter the user can correct — not a silent wrong
 * total.
 */
public enum Rounding {

    /** Ties go to the neighbour with an even last digit. Banker's rounding. */
    HALF_EVEN(RoundingMode.HALF_EVEN),

    /** Ties go away from zero. The usual choice for a final invoice amount. */
    HALF_UP(RoundingMode.HALF_UP),

    /** Ties go towards zero. */
    HALF_DOWN(RoundingMode.HALF_DOWN),

    /** Always away from zero. */
    UP(RoundingMode.UP),

    /** Always towards zero — truncation. */
    DOWN(RoundingMode.DOWN),

    /** Always towards positive infinity. */
    CEILING(RoundingMode.CEILING),

    /** Always towards negative infinity. */
    FLOOR(RoundingMode.FLOOR);

    private final RoundingMode mode;

    Rounding(RoundingMode mode) {
        this.mode = mode;
    }

    public RoundingMode mode() {
        return mode;
    }
}
