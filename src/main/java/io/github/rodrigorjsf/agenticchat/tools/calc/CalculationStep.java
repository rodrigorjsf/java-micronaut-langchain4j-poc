package io.github.rodrigorjsf.agenticchat.tools.calc;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * One step of a calculation. Steps run in array order, and a later step may name
 * an earlier one's result.
 *
 * <p><b>Why {@code @Description} and not {@code @P}.</b> {@code @P} is declared
 * {@code @Target(PARAMETER)}, so it cannot annotate a record component at all —
 * it compiles nowhere near here. {@code @Description} targets {@code FIELD} and
 * reaches the record's backing field, and LangChain4j's schema derivation picks
 * it up for the nested object. A record component with no description reaches
 * the model as a bare type, and a bare {@code List<String> operands} is where the
 * {@code PERCENT_OF}/{@code ADD_PERCENT} order gets guessed. {@code
 * CalculatorSchemaTest} fails the day someone swaps one annotation for the other.
 *
 * <p><b>Three components, not four.</b> Literal numbers and references to earlier
 * steps share the single {@code operands} list. The tempting fourth component —
 * a separate {@code refs} list — loses on a schema fact rather than on taste:
 * every record component lands in the schema's {@code required} array and there
 * is no per-component way to opt out, so the majority of steps that reference
 * nothing would have to send an explicit {@code []}. An operand is a reference
 * when it starts with {@code #}, and a canonical decimal never can, so the two
 * kinds are told apart by construction rather than by a rule someone has to obey.
 *
 * <p><b>References are safe by construction, not by analysis.</b> {@code #id}
 * resolves only against a step that has <em>already run</em> in the single
 * forward pass. A forward reference and a self reference are both
 * {@code unknown_reference} for the same reason and by the same check. There is
 * no graph, no topological sort and no cycle detection here, because a cycle is
 * unrepresentable — the machinery those three imply would exist only to rule out
 * a state the ordering has already ruled out.
 *
 * <p>The component descriptions below use real line breaks rather than text-block
 * {@code \} continuations: a continuation line indented past the block's common
 * indent silently keeps that extra indent, and the stray spaces then travel in
 * every schema the model is sent.
 */
@Description("One step of the calculation. Steps run in array order, and any step may use the result of an earlier one.")
public record CalculationStep(

        @Description("""
                Short name for this step's result, so a later step can refer to it and so the
                answer can be read back. Lower-case letters, digits and underscores only, 1 to
                32 characters, unique within the call. Examples: subtotal, discount, icms, total.""")
        String id,

        // The operand ORDER of every fixed-arity operation is stated here, and this
        // is the only place the model can read it. Operation's own per-constant
        // javadoc is source-only — the generated schema is a bare JsonEnumSchema of
        // sixteen names — and an order it has to guess is one it gets wrong silently:
        // a swap passes every arity guard and returns a plausible number, which is
        // the exact failure this whole tool exists to remove. It costs tokens on
        // every turn because the tool is always visible; a wrong invoice costs more.
        @Description("""
                What this step does. Percent operands are always in percent units:
                15 means 15 percent, never 0.15.
                Order matters, and these take their operands in a fixed order:
                PERCENT_OF [percent, base] — "15% of 200" is ["15","200"] and gives 30.
                ADD_PERCENT [base, percent] — "200 plus 15%" is ["200","15"] and gives 230.
                SUBTRACT_PERCENT [base, percent] — a discount; ["200","15"] gives 170.
                PERCENT_CHANGE [from, to] — ["200","230"] gives 15, the change in percent.
                RATIO_PERCENT [part, whole] — ["30","200"] gives 15, the share in percent.
                SIMPLE_INTEREST, COMPOUND_INTEREST and INSTALLMENT_PAYMENT all take
                [principal, ratePerPeriodInPercent, numberOfPeriods] — 1000 at 2% a month
                for 12 months is ["1000","2","12"]. The first two return the final amount;
                INSTALLMENT_PAYMENT returns the payment due each period (Price table).
                ROUND [value, decimalPlaces] rounds a value so a later step can use the
                rounded figure — ["#linha","2"] for centavos. Use it only when the rounding
                is really part of the answer, such as an invoice that rounds each line
                before summing; otherwise let the chain run at full precision.
                SUBTRACT and DIVIDE take the first operand and then apply the rest to it
                left to right. NEGATE takes one operand. SUM, MULTIPLY, AVERAGE, MIN and
                MAX do not depend on order.""")
        Operation operation,

        @Description("""
                The operands, in order. Each entry is either a plain number or a reference to an
                earlier step written as # followed by that step's id, for example #subtotal.
                A number uses a plain decimal point and nothing else: no thousands separator, no
                currency symbol, no percent sign, no exponent. 1234.56 and -0.5 are valid;
                1.234,56 and 1,234.56 and R$ 10 and 10% and 1e3 are all rejected.""")
        List<String> operands) {
}
