package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Split;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures the {@code grader} role against the human-labelled calibration set, with the real model.
 *
 * <p>Tagged {@code evals}: it needs {@code OPENAI_API_KEY} and costs money, so it runs with
 * {@code ./mvnw test -Pevals -Dtest=GraderCalibrationEval}. The Grader is prompted exactly as the
 * scenario suite prompts it — the same instructions and the same train rows as few-shot examples —
 * and graded on the dev and test splits, never on train.
 *
 * <p>It prints, per criterion and per split, TPR and TNR apart, then the sizing shortfalls, and
 * writes the test-split counts with the current fingerprint to
 * {@code src/test/resources/evals/grader-calibration-record.json}. Commit that file: it is what
 * {@code GraderCalibrationFreshnessTest} checks the configured grader and agent against.
 *
 * <p>This eval gates nothing. Until {@link GraderCalibrationSet#shortfalls} is empty for every
 * criterion, the rates are too thin to trust, and the rubric stays report-only either way.
 */
@Tag("evals")
class GraderCalibrationEval {

    /** Surefire runs with the project base directory as the working directory: the repository root. */
    private static final Path RECORD = Path.of(System.getProperty("user.dir"))
            .resolve("src/test/resources" + CalibrationRecord.COMMITTED);

    @Test
    @DisplayName("grader calibration: TPR and TNR per rubric criterion against human labels")
    void measureTheGrader() throws IOException {
        var set = GraderCalibrationSet.loadCommitted();
        var rubricCriteria = ScenarioDataset.loadCommitted().stream()
                .flatMap(scenario -> scenario.expect().rubric().stream())
                .distinct()
                .toList();
        var shortfalls = set.shortfalls(rubricCriteria);
        boolean anythingToMeasure = set.criteria().stream()
                .anyMatch(criterion -> !criterion.in(Split.DEV).isEmpty() || !criterion.in(Split.TEST).isEmpty());
        if (!anythingToMeasure) {
            shortfalls.forEach(line -> System.out.println("  short: " + line));
        }
        assumeTrue(anythingToMeasure, "the calibration set holds no labelled dev or test row yet");

        try (var ctx = ApplicationContext.run()) {
            var registry = ctx.getBean(ChatModelRegistry.class);
            var grader = RubricGrader.withFewShot(registry.forRole("grader"), set);
            var dev = GraderCalibration.measure(set, Split.DEV, grader);
            var test = GraderCalibration.measure(set, Split.TEST, grader);

            System.out.printf("%ngrader calibration (%s)%n", CalibrationRecord.Fingerprint.current(registry, set));
            GraderCalibration.describe(dev).forEach(line -> System.out.println("  " + line));
            GraderCalibration.describe(test).forEach(line -> System.out.println("  " + line));
            shortfalls.forEach(line -> System.out.println("  short: " + line));

            var record = new CalibrationRecord(CalibrationRecord.Fingerprint.current(registry, set),
                    LocalDate.now().toString(), test);
            Files.writeString(RECORD, record.toJson(), StandardCharsets.UTF_8);
            System.out.printf("  record written to %s — review and commit it%n", RECORD);
        }
    }
}
