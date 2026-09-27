package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recalibration trigger, in the ordinary build: no network, no API key. Building the registry
 * does not call a provider, so fake keys are enough to read which models the roles name.
 *
 * <p>Once a calibration is committed, a change to the {@code grader} model, the grader's prompt (its
 * instructions or a train row) or the {@code agent} model turns this test red until
 * {@code GraderCalibrationEval} is re-run and its new record committed.
 */
class GraderCalibrationFreshnessTest {

    @Test
    @DisplayName("the committed grader calibration was taken under the grader and agent this build configures")
    void theCommittedCalibrationIsCurrent() {
        try (var ctx = ApplicationContext.run(Map.of(
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake"))) {
            var current = CalibrationRecord.Fingerprint.current(ctx.getBean(ChatModelRegistry.class),
                    GraderCalibrationSet.loadCommitted());

            assertThat(current.graderModel()).as("read from the configured grader role, as PROVIDER/model").matches("[A-Z_]+/.+");
            assertThat(CalibrationRecord.recalibrationReasons(CalibrationRecord.loadCommitted(), current))
                    .as("recalibrate the grader: run ./mvnw test -Pevals -Dtest=GraderCalibrationEval and commit "
                            + "src/test/resources" + CalibrationRecord.COMMITTED)
                    .isEmpty();
        }
    }
}
