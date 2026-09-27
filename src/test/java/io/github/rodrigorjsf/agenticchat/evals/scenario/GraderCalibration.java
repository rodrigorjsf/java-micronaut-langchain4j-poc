package io.github.rodrigorjsf.agenticchat.evals.scenario;

import io.github.rodrigorjsf.agenticchat.evals.scenario.GraderCalibrationSet.Split;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Measures the Grader against the human labels, one criterion at a time.
 *
 * <p>Two rates, never folded into one accuracy:
 *
 * <ul>
 *   <li><b>TPR</b> (true-positive rate): of the answers a person marked PASS, the share the Grader
 *   also marked PASS. A low TPR means the Grader fails good answers.</li>
 *   <li><b>TNR</b> (true-negative rate): of the answers a person marked FAIL, the share the Grader
 *   also marked FAIL. A low TNR means the Grader lets bad answers through.</li>
 * </ul>
 *
 * <p>A set that is 90% PASS rows gives a Grader that always says PASS 90% accuracy and a TNR of
 * zero; reporting the two apart is what exposes it. A row the Grader gave no verdict on
 * (UNGRADED) counts toward neither rate and is reported as its own number.
 */
public final class GraderCalibration {

    private GraderCalibration() {
    }

    /**
     * The Grader's agreement with the human labels on one criterion, over one split.
     *
     * @param truePasses   human PASS, Grader PASS
     * @param missedPasses human PASS, Grader FAIL
     * @param trueFails    human FAIL, Grader FAIL
     * @param missedFails  human FAIL, Grader PASS
     * @param ungraded     rows the Grader gave no verdict on
     */
    public record CriterionResult(String criterion, Split split, int truePasses, int missedPasses,
                                  int trueFails, int missedFails, int ungraded) {

        /** Empty when no human-PASS row got a verdict: the rate is undefined, not zero. */
        public OptionalDouble tpr() {
            return rate(truePasses, truePasses + missedPasses);
        }

        /** Empty when no human-FAIL row got a verdict: the rate is undefined, not zero. */
        public OptionalDouble tnr() {
            return rate(trueFails, trueFails + missedFails);
        }

        private static OptionalDouble rate(int hits, int total) {
            return total == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) hits / total);
        }
    }

    /**
     * Grades every row of {@code split}, criterion by criterion, and compares each verdict with the
     * human label.
     *
     * @throws IllegalArgumentException for {@link Split#TRAIN}: those rows are the Grader's
     *                                  few-shot examples, and a Grader measured on the examples it
     *                                  was shown scores itself on the answer key
     */
    public static List<CriterionResult> measure(GraderCalibrationSet set, Split split, RubricGrader grader) {
        if (split == Split.TRAIN) {
            throw new IllegalArgumentException("the train split holds the grader's few-shot examples and is never measured");
        }
        var results = new ArrayList<CriterionResult>();
        for (var criterion : set.criteria()) {
            int truePasses = 0;
            int missedPasses = 0;
            int trueFails = 0;
            int missedFails = 0;
            int ungraded = 0;
            for (var example : criterion.in(split)) {
                var verdict = grader.grade(List.of(criterion.criterion()), example.turns(), example.answer())
                        .getFirst().verdict();
                if (verdict == RubricVerdict.Verdict.UNGRADED) {
                    ungraded++;
                } else if (example.pass()) {
                    if (verdict == RubricVerdict.Verdict.PASS) {
                        truePasses++;
                    } else {
                        missedPasses++;
                    }
                } else if (verdict == RubricVerdict.Verdict.FAIL) {
                    trueFails++;
                } else {
                    missedFails++;
                }
            }
            results.add(new CriterionResult(criterion.criterion(), split, truePasses, missedPasses,
                    trueFails, missedFails, ungraded));
        }
        return List.copyOf(results);
    }

    /** One line per criterion: the two rates apart, each with the counts it was computed from. */
    public static List<String> describe(List<CriterionResult> results) {
        return results.stream()
                .map(result -> "[" + result.split().json() + "] " + result.criterion()
                        + ": TPR " + rate(result.tpr(), result.truePasses(), result.truePasses() + result.missedPasses(), "PASS")
                        + ", TNR " + rate(result.tnr(), result.trueFails(), result.trueFails() + result.missedFails(), "FAIL")
                        + ", " + result.ungraded() + " ungraded")
                .toList();
    }

    private static String rate(OptionalDouble rate, int hits, int total, String label) {
        return rate.isEmpty()
                ? "n/a (no " + label + "-labelled row got a verdict)"
                : String.format(Locale.ROOT, "%.3f (%d/%d)", rate.getAsDouble(), hits, total);
    }
}
