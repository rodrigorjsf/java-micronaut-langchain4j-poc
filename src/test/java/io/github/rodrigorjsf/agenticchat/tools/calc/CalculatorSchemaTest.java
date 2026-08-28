package io.github.rodrigorjsf.agenticchat.tools.calc;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model is actually handed.
 *
 * <p>Every other test in this package exercises Java calling Java. This one
 * exercises the only surface the model ever sees, and it is the test that fails
 * the day someone swaps {@code @Description} for {@code @P} on a record
 * component — a change that compiles, passes every arithmetic test, and silently
 * ships a schema in which {@code operands} has no documentation and the
 * {@code PERCENT_OF}/{@code ADD_PERCENT} operand order is a coin flip.
 */
class CalculatorSchemaTest {

    private static final ToolSpecification SPEC = specFor("calculate");

    private static ToolSpecification specFor(String methodName) {
        Method method = Arrays.stream(CalculatorTools.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return ToolSpecifications.toolSpecificationFrom(method);
    }

    private static JsonObjectSchema stepSchema() {
        var steps = (JsonArraySchema) SPEC.parameters().properties().get("steps");
        return (JsonObjectSchema) steps.items();
    }

    @Test
    @DisplayName("the tool is named calculate and carries a description the model can route on")
    void theToolIsNamedAndDescribed() {
        assertThat(SPEC.name()).isEqualTo("calculate");
        assertThat(SPEC.description()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("steps is an array of objects, so a whole calculation is one call")
    void stepsIsAnArrayOfObjects() {
        JsonSchemaElement steps = SPEC.parameters().properties().get("steps");

        assertThat(steps)
                .as("a List<CalculationStep> parameter must reach the model as an array")
                .isInstanceOf(JsonArraySchema.class);
        assertThat(((JsonArraySchema) steps).items())
                .as("the array's element type must be the record, not a bare string")
                .isInstanceOf(JsonObjectSchema.class);
        assertThat(stepSchema().properties())
                .containsOnlyKeys("id", "operation", "operands");
    }

    @Test
    @DisplayName("operands is a nested array of strings")
    void operandsIsANestedArray() {
        JsonSchemaElement operands = stepSchema().properties().get("operands");

        assertThat(operands).isInstanceOf(JsonArraySchema.class);
    }

    @Test
    @DisplayName("the operation enum reaches the model with all seventeen values")
    void theOperationEnumCarriesEveryValue() {
        JsonSchemaElement operation = stepSchema().properties().get("operation");

        assertThat(operation).isInstanceOf(JsonEnumSchema.class);
        // Named one by one rather than derived from Operation.values(): a test that
        // reads the enum it is checking agrees with any future edit to that enum,
        // including the accidental ones. §3 of the contract froze this set at 16;
        // ROUND is the seventeenth, added after a review found that reporting a
        // rounded figure and being able to feed one forward are different things.
        assertThat(((JsonEnumSchema) operation).enumValues()).containsExactlyInAnyOrder(
                "SUM", "SUBTRACT", "MULTIPLY", "DIVIDE", "NEGATE", "AVERAGE", "MIN", "MAX",
                "PERCENT_OF", "ADD_PERCENT", "SUBTRACT_PERCENT", "PERCENT_CHANGE",
                "RATIO_PERCENT", "SIMPLE_INTEREST", "COMPOUND_INTEREST", "INSTALLMENT_PAYMENT",
                "ROUND");
    }

    @Test
    @DisplayName("every order-sensitive operation states its operand order where the model reads it")
    void theOperationDescriptionCarriesTheOperandOrders() {
        // The finding this test exists for: Operation's per-constant javadoc is
        // source-only, so the model receives a bare enum of names. An order it has to
        // guess is one it gets wrong silently — a swap passes every arity guard and
        // returns a plausible number. This description is the only place it can read
        // them, so a future edit that trims it for tokens goes red here.
        String description = stepSchema().properties().get("operation").description();

        assertThat(description).isNotNull();
        assertThat(description)
                .contains("PERCENT_OF [percent, base]")
                .contains("ADD_PERCENT [base, percent]")
                .contains("SUBTRACT_PERCENT [base, percent]")
                .contains("PERCENT_CHANGE [from, to]")
                .contains("RATIO_PERCENT [part, whole]")
                .contains("[principal, ratePerPeriodInPercent, numberOfPeriods]")
                .contains("ROUND [value, decimalPlaces]");
        // The two mirrored ones carry a worked figure, because naming the slots is
        // what separates 230 from 45 and the names alone read as interchangeable.
        assertThat(description).contains("gives 30").contains("gives 230");
    }

    @Test
    @DisplayName("currency reaches the model as an enum of BRL and USD")
    void theCurrencyEnumCarriesBothValues() {
        JsonSchemaElement currency = SPEC.parameters().properties().get("currency");

        assertThat(currency).isInstanceOf(JsonEnumSchema.class);
        assertThat(((JsonEnumSchema) currency).enumValues()).containsExactlyInAnyOrder("BRL", "USD");
    }

    @Test
    @DisplayName("rounding offers the seven safe modes and never UNNECESSARY")
    void theRoundingEnumExcludesUnnecessary() {
        JsonSchemaElement rounding = SPEC.parameters().properties().get("rounding");

        assertThat(rounding).isInstanceOf(JsonEnumSchema.class);
        // UNNECESSARY is not a rounding mode, it is an assertion that no rounding is
        // needed, and it throws on the first inexact result. Offering it to the model
        // would be offering a value the tool cannot survive.
        assertThat(((JsonEnumSchema) rounding).enumValues())
                .containsExactlyInAnyOrder(
                        "HALF_EVEN", "HALF_UP", "HALF_DOWN", "UP", "DOWN", "CEILING", "FLOOR")
                .doesNotContain("UNNECESSARY");
    }

    @Test
    @DisplayName("steps and currency are required; rounding is the one optional parameter")
    void roundingIsTheOnlyOptionalParameter() {
        List<String> required = SPEC.parameters().required();

        assertThat(required).contains("steps", "currency");
        // "Mandatory for the model to think about, defaulted for the model that does
        // not" is expressed as @P(required = false, defaultValue = "HALF_EVEN"), and
        // this assertion is the only proof that the annotation actually does it.
        assertThat(required)
                .as("rounding must stay optional, or every call has to pick a mode")
                .doesNotContain("rounding");
    }

    @Test
    @DisplayName("every parameter and every nested record component documents itself")
    void nothingReachesTheModelUndocumented() {
        SPEC.parameters().properties().forEach((name, schema) ->
                assertThat(descriptionOf(schema))
                        .as("parameter %s must document its format", name)
                        .isNotNull()
                        .isNotBlank());

        // The nested pass is the point of this test: a record component silently loses
        // its description if @Description is replaced by an annotation that does not
        // target FIELD, and nothing else in the suite notices.
        stepSchema().properties().forEach((name, schema) ->
                assertThat(descriptionOf(schema))
                        .as("record component %s must document its format", name)
                        .isNotNull()
                        .isNotBlank());
    }

    /**
     * The default branch throws rather than falling back to {@code toString()}.
     * A fallback here is worse than no test at all: {@code toString()} is never
     * null and never blank, so an undescribed schema would pass by printing
     * itself — which is precisely what this test was written to catch.
     */
    private static String descriptionOf(JsonSchemaElement schema) {
        return switch (schema) {
            case JsonStringSchema s -> s.description();
            case JsonArraySchema a -> a.description();
            case JsonEnumSchema e -> e.description();
            case JsonObjectSchema o -> o.description();
            default -> throw new AssertionError(
                    "unhandled schema type " + schema.getClass() + "; add a case rather than a fallback");
        };
    }
}
