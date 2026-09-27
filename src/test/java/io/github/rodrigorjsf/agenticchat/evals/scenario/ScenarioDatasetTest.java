package io.github.rodrigorjsf.agenticchat.evals.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioDatasetTest {

    @Test
    @DisplayName("the committed weather row loads with every field of the row shape")
    void loadsTheCommittedWeatherRow() {
        var scenarios = ScenarioDataset.loadCommitted();

        var weather = scenarios.stream()
                .filter(s -> s.id().equals("weather-happy-forecast-tomorrow"))
                .findFirst()
                .orElseThrow();

        assertThat(weather.domains()).containsExactly("weather");
        assertThat(weather.kind()).isEqualTo("happy");
        assertThat(weather.source()).isEqualTo("synthetic");
        assertThat(weather.turns()).hasSize(1);
        assertThat(weather.expect().trajectory().outcome()).isEqualTo("ANSWERED");
        assertThat(weather.expect().trajectory().toolsCalled()).containsExactly("get_weather");
        assertThat(weather.critical()).isTrue();
    }

    @Test
    @DisplayName("an upstream-failure row declares which catalogue key it fakes and with which failure shape")
    void anUpstreamFailureRowDeclaresItsOverride() {
        var scenarios = ScenarioDataset.loadCommitted();

        var faked = scenarios.stream()
                .filter(s -> s.kind().equals("upstream-failure"))
                .map(Scenario::upstream)
                .toList();

        assertThat(faked).extracting(Scenario.UpstreamFailure::catalogueKey)
                .containsOnly("open-meteo-forecast");
        assertThat(faked).extracting(Scenario.UpstreamFailure::failure)
                .containsExactlyInAnyOrder(Scenario.FailureShape.values());
    }

    @Test
    @DisplayName("a row without an upstream override runs against the real upstream")
    void aHappyRowFakesNothing() {
        var weather = ScenarioDataset.loadCommitted().stream()
                .filter(s -> s.id().equals("weather-happy-forecast-tomorrow"))
                .findFirst()
                .orElseThrow();

        assertThat(weather.upstream()).isNull();
    }

    @Test
    @DisplayName("an override naming a key outside the catalogue is refused, so it cannot fake nothing and report a fake")
    void anOverrideOutsideTheCatalogueIsRefused() {
        var typo = new Scenario.UpstreamFailure("open-meteo-forcast", Scenario.FailureShape.SERVER_ERROR);

        assertThatThrownBy(() -> typo.properties(URI.create("http://localhost:1"), Set.of("open-meteo-forecast")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("open-meteo-forcast");
    }

    @Test
    @DisplayName("rows come back in stable order: files by name, rows in file order")
    void ordersFilesByNameAndRowsByPosition(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("b-domain.json"), "[" + row("b1") + "]");
        Files.writeString(dir.resolve("a-domain.json"), "[" + row("a2") + "," + row("a1") + "]");

        var ids = ScenarioDataset.load(dir).stream().map(Scenario::id).toList();

        assertThat(ids).containsExactly("a2", "a1", "b1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRows")
    @DisplayName("an invalid row fails loading, naming the row and the reason")
    void anInvalidRowFailsLoadingWithItsIdAndReason(String reason, String row, String expectedMessage,
                                                    @TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("d.json"), "[" + row("fine") + "," + row + "]");

        assertThatThrownBy(() -> ScenarioDataset.load(dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("d.json")
                .hasMessageContaining(expectedMessage);
    }

    static Stream<Arguments> invalidRows() {
        return Stream.of(
                Arguments.of("unknown kind", row("r1").replace("\"kind\":\"happy\"", "\"kind\":\"sad\""),
                        "row 'r1': unknown kind 'sad'"),
                Arguments.of("unknown source", row("r2").replace("\"source\":\"synthetic\"", "\"source\":\"guess\""),
                        "row 'r2': unknown source 'guess'"),
                Arguments.of("missing dependsOn", row("r3").replace("\"dependsOn\":[\"get_weather\"],", ""),
                        "row 'r3': dependsOn is missing or empty"),
                Arguments.of("empty dependsOn", row("r4").replace("[\"get_weather\"]", "[]"),
                        "row 'r4': dependsOn is missing or empty"),
                Arguments.of("no domains", row("r5").replace("[\"d\"]", "[]"),
                        "row 'r5': domains is missing or empty"),
                Arguments.of("no turns", row("r6").replace("[\"oi\"]", "[]"),
                        "row 'r6': turns is missing or empty"),
                Arguments.of("no expected outcome", row("r7").replace("\"outcome\":\"ANSWERED\",", ""),
                        "row 'r7': expect.trajectory.outcome is missing"),
                Arguments.of("no id", row("").replace("\"id\":\"\",", ""),
                        "row 2: id is missing"),
                Arguments.of("duplicate id", row("fine"),
                        "row 'fine': id is already used by an earlier row"),
                Arguments.of("upstream-failure without upstream",
                        row("r8").replace("\"kind\":\"happy\"", "\"kind\":\"upstream-failure\""),
                        "row 'r8': kind upstream-failure requires an upstream override"),
                Arguments.of("upstream on another kind",
                        row("r9").replace("\"critical\":false",
                                "\"critical\":false,\"upstream\":{\"catalogueKey\":\"k\",\"failure\":\"timeout\"}"),
                        "row 'r9': an upstream override is only allowed on kind upstream-failure"),
                Arguments.of("multi-turn with one turn",
                        row("r10").replace("\"kind\":\"happy\"", "\"kind\":\"multi-turn\""),
                        "row 'r10': kind multi-turn needs at least two turns"));
    }

    private static String row(String id) {
        return """
                {"id":"%s","domains":["d"],"kind":"happy","source":"synthetic",
                 "turns":["oi"],"expect":{"trajectory":{"outcome":"ANSWERED","toolsCalled":[]}},
                 "dependsOn":["get_weather"],"critical":false}""".formatted(id);
    }
}
