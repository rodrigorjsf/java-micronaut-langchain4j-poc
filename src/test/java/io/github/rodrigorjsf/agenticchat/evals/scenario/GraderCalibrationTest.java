package io.github.rodrigorjsf.agenticchat.evals.scenario;

import dev.langchain4j.data.message.AiMessage;
import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Criterion;
import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Example;
import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Split;
import io.github.rodrigorjsf.agenticchat.testsupport.ScriptedChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraderCalibrationTest {

    private static final String RAIN = "The answer says whether it will rain.";
    private static final String NO_FIGURE = "The answer invents no forecast figure.";

    /** A grader that says PASS whenever the answer contains "yes", and FAIL otherwise. */
    private final ScriptedChatModel model = new ScriptedChatModel().routeBy(request ->
            AiMessage.from(request.messages().getLast().toString().contains("yes")
                    ? "{\"pass\":true,\"critique\":\"ok\"}"
                    : "{\"pass\":false,\"critique\":\"no\"}"));

    private static Example example(String id, Split split, String answer, boolean humanPass) {
        return new Example(id, split, List.of("Vai chover amanhã?"), answer, humanPass, "", "rodrigo");
    }

    @Test
    @DisplayName("TPR and TNR are measured separately for each criterion, against the human labels")
    void tprAndTnrPerCriterion() {
        var set = new GraderCalibrationSet(List.of(
                new Criterion(RAIN, List.of(
                        // three human PASS rows: the grader agrees on two -> TPR 2/3
                        example("r1", Split.DEV, "yes, rain", true),
                        example("r2", Split.DEV, "yes, 12 mm", true),
                        example("r3", Split.DEV, "12 mm", true),
                        // two human FAIL rows: the grader catches one -> TNR 1/2
                        example("r4", Split.DEV, "figures only", false),
                        example("r5", Split.DEV, "yes, but wrong city", false))),
                new Criterion(NO_FIGURE, List.of(
                        example("f1", Split.DEV, "yes", true),
                        example("f2", Split.DEV, "31 mm made up", false)))));

        var results = GraderCalibration.measure(set, Split.DEV, new RubricGrader(model));

        assertThat(results).extracting(GraderCalibration.CriterionResult::criterion).containsExactly(RAIN, NO_FIGURE);
        var rain = results.getFirst();
        assertThat(rain.truePasses()).isEqualTo(2);
        assertThat(rain.missedPasses()).isEqualTo(1);
        assertThat(rain.trueFails()).isEqualTo(1);
        assertThat(rain.missedFails()).isEqualTo(1);
        assertThat(rain.tpr()).hasValueCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(rain.tnr()).hasValueCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        var figure = results.getLast();
        assertThat(figure.tpr()).hasValue(1.0);
        assertThat(figure.tnr()).hasValue(1.0);
    }

    @Test
    @DisplayName("only the measured split is graded, and a grader that gives no verdict is counted apart, not as an error")
    void onlyTheSplitIsGradedAndUngradedIsCountedApart() {
        var set = new GraderCalibrationSet(List.of(new Criterion(RAIN, List.of(
                example("t1", Split.TEST, "yes", true),
                example("d1", Split.DEV, "yes", true),
                example("t2", Split.TEST, "no", false)))));
        var unreadable = new ScriptedChatModel().fallbackTo("I think it is fine.");

        var result = GraderCalibration.measure(set, Split.TEST, new RubricGrader(unreadable)).getFirst();

        assertThat(unreadable.callCount()).as("the dev row is not graded when measuring test").isEqualTo(2);
        assertThat(result.ungraded()).isEqualTo(2);
        assertThat(result.tpr()).as("no verdict on a PASS row: TPR is undefined, not zero").isEmpty();
        assertThat(result.tnr()).isEmpty();
    }

    @Test
    @DisplayName("measuring on the train split is refused: the grader was shown those rows as examples")
    void theTrainSplitIsNeverMeasured() {
        var set = new GraderCalibrationSet(List.of(new Criterion(RAIN, List.of(example("x", Split.TRAIN, "yes", true)))));

        assertThatThrownBy(() -> GraderCalibration.measure(set, Split.TRAIN, new RubricGrader(model)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("train");
    }

    @Test
    @DisplayName("each criterion is described with its TPR and TNR apart, as fractions, and an undefined rate says why")
    void describesEachCriterion() {
        var measured = new GraderCalibration.CriterionResult(RAIN, Split.TEST, 2, 1, 1, 1, 0);
        var unmeasured = new GraderCalibration.CriterionResult(NO_FIGURE, Split.TEST, 0, 0, 3, 0, 2);

        assertThat(GraderCalibration.describe(List.of(measured, unmeasured))).containsExactly(
                "[test] " + RAIN + ": TPR 0.667 (2/3), TNR 0.500 (1/2), 0 ungraded",
                "[test] " + NO_FIGURE + ": TPR n/a (no PASS-labelled row got a verdict), TNR 1.000 (3/3), 2 ungraded");
    }
}
