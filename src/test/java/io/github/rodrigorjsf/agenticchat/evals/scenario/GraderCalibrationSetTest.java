package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Criterion;
import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Example;
import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Split;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraderCalibrationSetTest {

    private static GraderCalibrationSet parse(String json) {
        return GraderCalibrationSet.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.json");
    }

    private static String row(String fields) {
        return "{\"criteria\":[{\"criterion\":\"C\",\"examples\":[" + fields + "]}]}";
    }

    private static final String VALID = """
            {"id":"a","split":"dev","turns":["oi"],"answer":"olá","pass":true,"critique":"ok","labelledBy":"rodrigo"}""";

    @Test
    @DisplayName("a valid row loads with its split and human label")
    void aValidRowLoads() {
        var set = parse(row(VALID));

        var example = set.criteria().getFirst().examples().getFirst();
        assertThat(example.split()).isEqualTo(Split.DEV);
        assertThat(example.pass()).isTrue();
        assertThat(example.labelledBy()).isEqualTo("rodrigo");
    }

    @Test
    @DisplayName("an invalid row fails the load, naming the file, the row and the rule it breaks")
    void invalidRowsFailTheLoad() {
        assertThatThrownBy(() -> parse(row(VALID.replace("\"dev\"", "\"holdout\""))))
                .hasMessageContaining("test.json").hasMessageContaining("holdout");
        assertThatThrownBy(() -> parse(row(VALID.replace("\"pass\":true,", ""))))
                .hasMessageContaining("row 'a'").hasMessageContaining("pass");
        assertThatThrownBy(() -> parse(row(VALID.replace("\"labelledBy\":\"rodrigo\"", "\"labelledBy\":\"\""))))
                .hasMessageContaining("labelledBy");
        assertThatThrownBy(() -> parse(row(VALID.replace("\"answer\":\"olá\",", ""))))
                .hasMessageContaining("answer");
        assertThatThrownBy(() -> parse(row(VALID + "," + VALID)))
                .hasMessageContaining("row 'a'").hasMessageContaining("already used");
        assertThatThrownBy(() -> parse("{\"criteria\":[{\"criterion\":\"C\",\"examples\":[]},{\"criterion\":\"C\",\"examples\":[]}]}"))
                .hasMessageContaining("criterion 'C'").hasMessageContaining("more than once");
    }

    private static Example example(int i, Split split, boolean pass) {
        return new Example("e" + i, split, List.of("oi"), "olá", pass, "", "rodrigo");
    }

    @Test
    @DisplayName("a criterion is short until it has 60 rows and both labels in dev and in test")
    void theSizingFloor() {
        var sized = new ArrayList<Example>();
        for (int i = 0; i < 60; i++) {
            sized.add(example(i, Split.values()[i % 3], i % 2 == 0));
        }
        var small = List.of(example(100, Split.TEST, true), example(101, Split.DEV, true));
        var set = new GraderCalibrationSet(List.of(new Criterion("sized", sized), new Criterion("small", small)));

        assertThat(set.shortfalls(List.of("sized", "small", "never labelled"))).containsExactly(
                "small: 2 labelled rows, 58 short of 60",
                "small: no FAIL-labelled row in dev",
                "small: no FAIL-labelled row in test",
                "never labelled: 0 labelled rows, 60 short of 60");
    }

    @Test
    @DisplayName("the committed calibration set loads and names every rubric criterion the scenarios use")
    void theCommittedSetLoads() {
        var set = GraderCalibrationSet.loadCommitted();
        var rubricCriteria = ScenarioDataset.loadCommitted().stream()
                .flatMap(scenario -> scenario.expect().rubric().stream())
                .toList();

        assertThat(set.criteria()).extracting(Criterion::criterion).containsAll(rubricCriteria);
    }
}
