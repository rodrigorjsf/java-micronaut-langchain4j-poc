package io.github.rodrigorjsf.agenticchat.evals.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioSelectionTest {

    private static final List<Scenario> DATASET = List.of(
            row("weather-happy", List.of("weather"), "geo-and-weather", "get_weather"),
            row("cnpj-happy", List.of("cnpj"), "brazil-civic-data", "lookup_cnpj"),
            row("trip-cross", List.of("weather", "holidays"), "trip-briefing", "trip_briefing"),
            row("weather-timeout", List.of("weather"), "get_weather", "open-meteo-forecast"));

    @Test
    @DisplayName("with no property set, every scenario runs")
    void noFilterSelectsEverything() {
        var selection = ScenarioSelection.fromProperties(key -> null, ref -> Set.of());

        assertThat(ids(selection.apply(DATASET)))
                .containsExactly("weather-happy", "cnpj-happy", "trip-cross", "weather-timeout");
    }

    @Test
    @DisplayName("-Dscenario.domain keeps every row of that domain, cross-domain rows included")
    void domainSelectsItsRows() {
        var selection = ScenarioSelection.fromProperties(Map.of("scenario.domain", "weather")::get, ref -> Set.of());

        assertThat(ids(selection.apply(DATASET))).containsExactly("weather-happy", "trip-cross", "weather-timeout");
    }

    @Test
    @DisplayName("-Dscenario.ids keeps the listed rows, in dataset order")
    void idsSelectTheirRows() {
        var selection = ScenarioSelection.fromProperties(
                Map.of("scenario.ids", "weather-timeout, cnpj-happy")::get, ref -> Set.of());

        assertThat(ids(selection.apply(DATASET))).containsExactly("cnpj-happy", "weather-timeout");
    }

    @Test
    @DisplayName("an id no row has is refused rather than silently running nothing")
    void anUnknownIdIsRefused() {
        var selection = ScenarioSelection.fromProperties(Map.of("scenario.ids", "weather-hapy")::get, ref -> Set.of());

        assertThatThrownBy(() -> selection.apply(DATASET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weather-hapy");
    }

    @Test
    @DisplayName("a domain no row has is refused rather than silently running nothing")
    void anUnknownDomainIsRefused() {
        var selection = ScenarioSelection.fromProperties(Map.of("scenario.domain", "wether")::get, ref -> Set.of());

        assertThatThrownBy(() -> selection.apply(DATASET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wether");
    }

    @Test
    @DisplayName("diff mode keeps the rows that depend on an artifact changed since the ref")
    void diffModeSelectsRowsOfChangedArtifacts() {
        var selection = ScenarioSelection.fromProperties(Map.of("scenario.changedSince", "main")::get,
                ref -> ref.equals("main") ? Set.of("get_weather") : Set.of());

        assertThat(ids(selection.apply(DATASET))).containsExactly("weather-happy", "weather-timeout");
        assertThat(selection.changedArtifacts()).containsExactly("get_weather");
    }

    @Test
    @DisplayName("diff mode with nothing changed selects nothing")
    void diffModeWithNoChangeSelectsNothing() {
        var selection = ScenarioSelection.fromProperties(Map.of("scenario.changedSince", "main")::get, ref -> Set.of());

        assertThat(selection.apply(DATASET)).isEmpty();
    }

    @Test
    @DisplayName("filters combine: a domain and a diff keep only the rows both select")
    void filtersCombine() {
        var selection = ScenarioSelection.fromProperties(
                Map.of("scenario.domain", "weather", "scenario.changedSince", "main")::get,
                ref -> Set.of("trip_briefing", "lookup_cnpj"));

        assertThat(ids(selection.apply(DATASET))).containsExactly("trip-cross");
    }

    @Test
    @DisplayName("the selection describes itself for the report")
    void describesItself() {
        assertThat(ScenarioSelection.fromProperties(key -> null, ref -> Set.of()).describe())
                .isEqualTo("every scenario");
        assertThat(ScenarioSelection.fromProperties(
                Map.of("scenario.domain", "weather", "scenario.changedSince", "main")::get,
                ref -> Set.of("get_weather")).describe())
                .isEqualTo("domain weather; depends on an artifact changed since main: [get_weather]");
    }

    private static List<String> ids(List<Scenario> scenarios) {
        return scenarios.stream().map(Scenario::id).toList();
    }

    private static Scenario row(String id, List<String> domains, String... dependsOn) {
        return new Scenario(id, domains, "happy", "synthetic", List.of("oi"),
                new Scenario.Expectation(new Scenario.TrajectoryExpectation("ANSWERED", List.of()), null),
                List.of(dependsOn), false, null);
    }
}
