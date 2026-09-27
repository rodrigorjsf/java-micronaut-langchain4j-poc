package io.github.rodrigorjsf.agenticchat.evals.scenario;

/**
 * The Grader's verdict on one rubric criterion: binary when it could grade, with a short critique
 * either way.
 *
 * @param criterion the rubric line, as the row wrote it
 * @param verdict   pass or fail, or {@link Verdict#UNGRADED} when no verdict could be read
 * @param critique  the Grader's reason, or why the criterion went ungraded
 */
public record RubricVerdict(String criterion, Verdict verdict, String critique) {

    public enum Verdict {
        PASS,
        FAIL,
        /** The Grader errored or answered something that is not a verdict: it measured nothing. */
        UNGRADED
    }

    static RubricVerdict ungraded(String criterion, String reason) {
        return new RubricVerdict(criterion, Verdict.UNGRADED, reason);
    }
}
