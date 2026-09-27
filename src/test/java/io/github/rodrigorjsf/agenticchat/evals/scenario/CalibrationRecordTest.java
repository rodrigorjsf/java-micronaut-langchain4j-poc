package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.CalibrationRecord.Fingerprint;
import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Split;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CalibrationRecordTest {

    private static final Fingerprint CALIBRATED = new Fingerprint("OPENAI/gpt-4o-mini", "sha256:aaa", "GOOGLE/gemini-3.1-flash-lite");

    private static Optional<CalibrationRecord> recordUnder(Fingerprint fingerprint) {
        return Optional.of(new CalibrationRecord(fingerprint, "2026-09-27", List.of()));
    }

    @Test
    @DisplayName("a calibration taken under the current grader model, grader prompt and agent model is current")
    void sameFingerprintIsCurrent() {
        assertThat(CalibrationRecord.recalibrationReasons(recordUnder(CALIBRATED), CALIBRATED)).isEmpty();
    }

    @Test
    @DisplayName("changing the grader model, its prompt or the agent model each demands a recalibration, naming what changed")
    void eachChangeTriggersRecalibration() {
        var graderModel = new Fingerprint("OPENAI/gpt-5-mini", "sha256:aaa", "GOOGLE/gemini-3.1-flash-lite");
        var graderPrompt = new Fingerprint("OPENAI/gpt-4o-mini", "sha256:bbb", "GOOGLE/gemini-3.1-flash-lite");
        var agentModel = new Fingerprint("OPENAI/gpt-4o-mini", "sha256:aaa", "GOOGLE/gemini-3.5-flash");

        assertThat(CalibrationRecord.recalibrationReasons(recordUnder(CALIBRATED), graderModel))
                .containsExactly("the grader model changed from OPENAI/gpt-4o-mini to OPENAI/gpt-5-mini");
        assertThat(CalibrationRecord.recalibrationReasons(recordUnder(CALIBRATED), graderPrompt))
                .containsExactly("the grader prompt changed (its instructions or a train example)");
        assertThat(CalibrationRecord.recalibrationReasons(recordUnder(CALIBRATED), agentModel))
                .containsExactly("the agent model changed from GOOGLE/gemini-3.1-flash-lite to GOOGLE/gemini-3.5-flash");
    }

    @Test
    @DisplayName("with no calibration committed there is nothing to go stale")
    void noRecordNeedsNoRecalibration() {
        assertThat(CalibrationRecord.recalibrationReasons(Optional.empty(), CALIBRATED)).isEmpty();
    }

    @Test
    @DisplayName("a record survives a round trip through its committed JSON form")
    void roundTrip() {
        var result = new GraderCalibration.CriterionResult("C", Split.TEST, 2, 1, 1, 1, 0);
        var record = new CalibrationRecord(CALIBRATED, "2026-09-27", List.of(result));

        assertThat(CalibrationRecord.fromJson(record.toJson())).isEqualTo(record);
    }
}
