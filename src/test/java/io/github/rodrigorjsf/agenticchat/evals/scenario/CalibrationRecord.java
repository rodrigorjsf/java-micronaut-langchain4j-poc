package io.github.rodrigorjsf.agenticchat.evals.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.rodrigorjsf.agenticchat.llm.ChatModelRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The last calibration of the Grader: what it measured, and under which Grader it measured it.
 *
 * <p>A TPR and TNR describe one grader model, prompted one way, grading one agent's answers. Change
 * any of the three and the numbers describe a grader that no longer exists: a new grader model
 * disagrees with people differently, a new prompt or train example is a new grader, and a new
 * agent model writes answers unlike the ones that were labelled. {@link #recalibrationReasons}
 * compares the committed record's {@link Fingerprint} with the current one and names every change;
 * {@code GraderCalibrationFreshnessTest} fails the ordinary build while any is unanswered.
 *
 * <p>Committed at {@value #COMMITTED} by {@code GraderCalibrationEval}, so a recalibration
 * arrives as a reviewable diff. Absent until the first calibration: nothing measured, nothing to go
 * stale.
 *
 * @param calibratedUnder the Grader the results describe
 * @param calibratedOn    ISO date of the run
 * @param results         per criterion, the test-split counts
 */
public record CalibrationRecord(Fingerprint calibratedUnder, String calibratedOn,
                                List<GraderCalibration.CriterionResult> results) {

    static final String COMMITTED = "/evals/grader-calibration-record.json";

    public CalibrationRecord {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /**
     * Everything a calibration depends on, besides the labels.
     *
     * @param graderModel  {@code PROVIDER/model-name} of the {@code grader} role
     * @param graderPrompt {@link RubricGrader#promptFingerprint} of the instructions and train rows
     * @param agentModel   {@code PROVIDER/model-name} of the {@code agent} role
     */
    public record Fingerprint(String graderModel, String graderPrompt, String agentModel) {

        /** The fingerprint of the configured roles, prompted with {@code calibration}'s train rows. */
        public static Fingerprint current(ChatModelRegistry registry, GraderCalibrationSet calibration) {
            return new Fingerprint(model(registry, "grader"), RubricGrader.promptFingerprint(calibration),
                    model(registry, "agent"));
        }

        private static String model(ChatModelRegistry registry, String role) {
            var config = registry.configFor(role);
            return config.provider() + "/" + config.modelName();
        }
    }

    /** The committed record, or empty when the Grader has never been calibrated. */
    public static Optional<CalibrationRecord> loadCommitted() {
        try (var in = CalibrationRecord.class.getResourceAsStream(COMMITTED)) {
            return in == null ? Optional.empty() : Optional.of(fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + COMMITTED, e);
        }
    }

    /**
     * Why the committed calibration no longer describes the current Grader — one reason per change —
     * or empty when it still does, or when there is no calibration to go stale.
     */
    public static List<String> recalibrationReasons(Optional<CalibrationRecord> record, Fingerprint current) {
        if (record.isEmpty()) {
            return List.of();
        }
        var then = record.get().calibratedUnder();
        var reasons = new ArrayList<String>();
        if (!then.graderModel().equals(current.graderModel())) {
            reasons.add("the grader model changed from " + then.graderModel() + " to " + current.graderModel());
        }
        if (!then.graderPrompt().equals(current.graderPrompt())) {
            reasons.add("the grader prompt changed (its instructions or a train example)");
        }
        if (!then.agentModel().equals(current.agentModel())) {
            reasons.add("the agent model changed from " + then.agentModel() + " to " + current.agentModel());
        }
        return List.copyOf(reasons);
    }

    public String toJson() {
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(this) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a calibration record always serializes", e);
        }
    }

    public static CalibrationRecord fromJson(String json) {
        try {
            return new ObjectMapper().readValue(json, CalibrationRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid calibration record " + COMMITTED + ": " + e.getOriginalMessage(), e);
        }
    }
}
