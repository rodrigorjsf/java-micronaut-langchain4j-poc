package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Criterion;
import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Example;
import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Split;
import io.github.rodrigorjsf.agenticchat.testsupport.ScriptedChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RubricGraderTest {

    private static final String RAIN = "The answer says whether it will rain.";

    private static Example example(String id, Split split, String answer, boolean pass, String critique) {
        return new Example(id, split, List.of("Vai chover amanhã?"), answer, pass, critique, "rodrigo");
    }

    private final GraderCalibrationSet set = new GraderCalibrationSet(List.of(
            new Criterion(RAIN, List.of(
                    example("train-pass", Split.TRAIN, "TRAIN-ANSWER-A: sim, vai chover", true, "Says it will rain."),
                    example("train-fail", Split.TRAIN, "TRAIN-ANSWER-B: 12 mm", false, "Only a figure."),
                    example("dev-row", Split.DEV, "DEV-ANSWER: talvez", false, "Hedges."),
                    example("test-row", Split.TEST, "TEST-ANSWER: sim", true, "Says it."))),
            new Criterion("Another criterion.", List.of(
                    example("other-train", Split.TRAIN, "OTHER-CRITERION-ANSWER", true, "fine")))));

    @Test
    @DisplayName("the grader's few-shot examples are the criterion's train rows, never a dev or test row")
    void fewShotExamplesComeOnlyFromTheTrainSplit() {
        var model = new ScriptedChatModel().fallbackTo("{\"pass\":true,\"critique\":\"ok\"}");

        RubricGrader.withFewShot(model, set).grade(List.of(RAIN), List.of("Vai chover amanhã?"), "sim");

        var sent = model.lastRequest().messages().toString();
        assertThat(sent)
                .contains("TRAIN-ANSWER-A", "Says it will rain.", "TRAIN-ANSWER-B", "Only a figure.")
                .doesNotContain("DEV-ANSWER", "TEST-ANSWER")
                .as("another criterion's examples do not leak into this one")
                .doesNotContain("OTHER-CRITERION-ANSWER");
    }

    @Test
    @DisplayName("a criterion with no train rows is graded zero-shot, exactly as without a calibration set")
    void aCriterionWithoutTrainRowsIsZeroShot() {
        var withSet = new ScriptedChatModel().fallbackTo("{\"pass\":true,\"critique\":\"ok\"}");
        var without = new ScriptedChatModel().fallbackTo("{\"pass\":true,\"critique\":\"ok\"}");

        RubricGrader.withFewShot(withSet, set).grade(List.of("Unlabelled criterion."), List.of("oi"), "olá");
        new RubricGrader(without).grade(List.of("Unlabelled criterion."), List.of("oi"), "olá");

        assertThat(withSet.lastRequest().messages()).isEqualTo(without.lastRequest().messages());
    }

    @Test
    @DisplayName("the prompt fingerprint changes when a train example changes, and ignores dev and test rows")
    void thePromptFingerprintFollowsTheTrainSplitOnly() {
        var devEdited = new GraderCalibrationSet(List.of(new Criterion(RAIN, List.of(
                set.criteria().getFirst().examples().get(0),
                set.criteria().getFirst().examples().get(1),
                example("dev-row", Split.DEV, "a different dev answer", true, "x"))),
                set.criteria().getLast()));
        var trainEdited = new GraderCalibrationSet(List.of(new Criterion(RAIN, List.of(
                example("train-pass", Split.TRAIN, "an edited train answer", true, "Says it will rain."),
                set.criteria().getFirst().examples().get(1))),
                set.criteria().getLast()));

        assertThat(RubricGrader.promptFingerprint(devEdited)).isEqualTo(RubricGrader.promptFingerprint(set));
        assertThat(RubricGrader.promptFingerprint(trainEdited)).isNotEqualTo(RubricGrader.promptFingerprint(set));
    }
}
