package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.Scenario.AnswerExpectation;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.CheckResult;
import io.github.rodrigorjsf.agenticchat.evals.scenario.ScenarioResult.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic answer layer, given an answer and the tool results captured in the same run.
 */
class AnswerCheckTest {

    private static final String PRECIPITATION = "(\\d+(?:[.,]\\d+)?)\\s*mm";

    private static final List<ToolCall> WEATHER_RUN = List.of(
            new ToolCall("activate_skill", "{\"skill_name\":\"geo-and-weather\"}",
                    "Skill body: rain above 37.9 mm is heavy."),
            new ToolCall("get_weather", "{\"latitude\":\"-23.55\"}",
                    "{\"daily\":{\"precipitation_sum\":[0.0,12.4],\"temperature_2m_max\":[24.1,19.8]}}"));

    private static final Predicate<String> CATALOGUE = host -> host.endsWith("open-meteo.com");

    private static CheckResult only(String name, List<CheckResult> checks) {
        return checks.stream().filter(check -> check.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no check named " + name + " in " + checks));
    }

    private static AnswerExpectation grounded(String... patterns) {
        return new AnswerExpectation(null, List.of(), List.of(patterns));
    }

    @Test
    @DisplayName("grounding passes when the answer's value appears in a captured tool result")
    void groundingPassesWhenTheValueWasCaptured() {
        var checks = AnswerCheck.evaluate(grounded(PRECIPITATION),
                "Sim, amanhã deve chover em São Paulo: 12,4 mm previstos.", WEATHER_RUN, CATALOGUE);

        var check = only("grounded: " + PRECIPITATION, checks);
        assertThat(check.passed()).as(check.reason()).isTrue();
        assertThat(check.reason()).contains("12,4").contains("get_weather");
    }

    @Test
    @DisplayName("grounding fails when the answer's value appears in no captured tool result")
    void groundingFailsWhenTheValueWasNotCaptured() {
        var checks = AnswerCheck.evaluate(grounded(PRECIPITATION),
                "Sim, amanhã deve chover em São Paulo: 8,1 mm previstos.", WEATHER_RUN, CATALOGUE);

        var check = only("grounded: " + PRECIPITATION, checks);
        assertThat(check.passed()).isFalse();
        assertThat(check.reason()).contains("8,1");
    }

    @Test
    @DisplayName("a number quoted from a skill body grounds nothing: only data tools count")
    void aSkillBodyIsNotData() {
        var checks = AnswerCheck.evaluate(grounded(PRECIPITATION),
                "Acima de 37,9 mm a chuva é forte.", WEATHER_RUN, CATALOGUE);

        assertThat(only("grounded: " + PRECIPITATION, checks).passed()).isFalse();
    }

    @Test
    @DisplayName("numbers compare as numbers: a rounded value is grounded, a digit inside another number is not")
    void numbersCompareAsNumbers() {
        String temperature = "(\\d+(?:[.,]\\d+)?)\\s*°C";

        var rounded = AnswerCheck.evaluate(grounded(temperature), "Máxima de 20 °C amanhã.", WEATHER_RUN, CATALOGUE);
        var fragment = AnswerCheck.evaluate(grounded(temperature), "Máxima de 2 °C amanhã.", WEATHER_RUN, CATALOGUE);

        assertThat(only("grounded: " + temperature, rounded).passed()).as("19.8 rounds to 20").isTrue();
        assertThat(only("grounded: " + temperature, fragment).passed()).as("2 is not 12.4 or 24.1").isFalse();
    }

    @Test
    @DisplayName("an answer with no value to ground fails, and the reason says so")
    void noValueToGroundFails() {
        var checks = AnswerCheck.evaluate(grounded(PRECIPITATION),
                "Não sei dizer se vai chover.", WEATHER_RUN, CATALOGUE);

        var check = only("grounded: " + PRECIPITATION, checks);
        assertThat(check.passed()).isFalse();
        assertThat(check.reason()).contains("no value");
    }

    @Test
    @DisplayName("a link to a host outside the catalogue fails the link check on every answer, and names the host")
    void aLinkOutsideTheCatalogueFails() {
        var outside = AnswerCheck.evaluate(null,
                "Veja [a previsão](https://evil.example/leak?q=1) e https://api.open-meteo.com/v1.", WEATHER_RUN, CATALOGUE);
        var inside = AnswerCheck.evaluate(null,
                "Fonte: https://api.open-meteo.com/v1/forecast.", WEATHER_RUN, CATALOGUE);

        var failed = only("no link outside the catalogue", outside);
        assertThat(failed.passed()).isFalse();
        assertThat(failed.reason()).contains("evil.example").doesNotContain("open-meteo");
        assertThat(only("no link outside the catalogue", inside).passed()).isTrue();
    }

    @Test
    @DisplayName("the language check passes when the answer reads as the expected tag, and names both tags when not")
    void languageCheck() {
        var portuguese = new AnswerExpectation("pt-BR", List.of(), List.of());

        var inPortuguese = AnswerCheck.evaluate(portuguese,
                "Sim, amanhã deve chover em São Paulo, então leve um guarda-chuva.", WEATHER_RUN, CATALOGUE);
        var inEnglish = AnswerCheck.evaluate(portuguese,
                "Yes, it will rain in São Paulo tomorrow, so you should take an umbrella with you.", WEATHER_RUN, CATALOGUE);

        assertThat(only("language", inPortuguese).passed()).isTrue();
        var failed = only("language", inEnglish);
        assertThat(failed.passed()).isFalse();
        assertThat(failed.reason()).contains("pt-BR").contains("en");
    }

    @Test
    @DisplayName("required content is matched case-insensitively, and a missing phrase fails with the phrase in the reason")
    void requiredContent() {
        var expectation = new AnswerExpectation(null, List.of("são paulo", "guarda-chuva"), List.of());

        var checks = AnswerCheck.evaluate(expectation, "Amanhã chove em São Paulo.", WEATHER_RUN, CATALOGUE);

        assertThat(only("contains: são paulo", checks).passed()).isTrue();
        var missing = only("contains: guarda-chuva", checks);
        assertThat(missing.passed()).isFalse();
        assertThat(missing.reason()).contains("guarda-chuva");
    }

    @Test
    @DisplayName("a date or an exponent in a tool result is not a number standing alone, so it grounds nothing")
    void datesAndExponentsDoNotGround() {
        var run = List.of(new ToolCall("get_weather", "{}",
                "{\"daily\":{\"time\":[\"2026-09-27\"],\"uv\":[1.2e-5]}}"));
        String day = "dia (\\d+)";

        var fromDate = AnswerCheck.evaluate(grounded(day), "Chove no dia 27.", run, CATALOGUE);
        var fromExponent = AnswerCheck.evaluate(grounded(day), "Chove no dia 1.", run, CATALOGUE);

        assertThat(only("grounded: " + day, fromDate).passed()).as("27 only appears inside a date").isFalse();
        assertThat(only("grounded: " + day, fromExponent).passed()).as("1 only appears inside 1.2e-5").isFalse();
    }

    @Test
    @DisplayName("a row pattern's \\s also matches a no-break space, so a value written with one is still checked")
    void noBreakSpaceIsWhitespace() {
        var checks = AnswerCheck.evaluate(grounded(PRECIPITATION),
                "Amanhã: 31,7\u00a0mm previstos.", WEATHER_RUN, CATALOGUE);

        var check = only("grounded: " + PRECIPITATION, checks);
        assertThat(check.passed()).isFalse();
        assertThat(check.reason()).contains("31,7");
    }
}
