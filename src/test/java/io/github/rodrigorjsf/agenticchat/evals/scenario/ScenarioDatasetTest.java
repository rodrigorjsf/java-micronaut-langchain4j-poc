package io.github.rodrigorjsf.agenticchat.evals.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("rows come back in stable order: files by name, rows in file order")
    void ordersFilesByNameAndRowsByPosition(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("b-domain.json"), "[" + row("b1") + "]");
        Files.writeString(dir.resolve("a-domain.json"), "[" + row("a2") + "," + row("a1") + "]");

        var ids = ScenarioDataset.load(dir).stream().map(Scenario::id).toList();

        assertThat(ids).containsExactly("a2", "a1", "b1");
    }

    private static String row(String id) {
        return """
                {"id":"%s","domains":["d"],"kind":"happy","source":"synthetic",
                 "turns":["oi"],"expect":{"trajectory":{"outcome":"ANSWERED","toolsCalled":[]}},
                 "dependsOn":[],"critical":false}""".formatted(id);
    }
}
