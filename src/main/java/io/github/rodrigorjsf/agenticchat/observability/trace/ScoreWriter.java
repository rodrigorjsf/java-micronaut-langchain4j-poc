package io.github.rodrigorjsf.agenticchat.observability.trace;

/**
 * The one interface the application uses to attach an evaluation to what it just did.
 *
 * <p>Separate from {@link AgentTracer} because the transport is separate. Everything else
 * in this package becomes a span and leaves over OTLP; a score cannot — Langfuse's
 * migration guide says so directly — so it travels over {@code POST /api/public/scores}
 * instead. Two destinations, one vocabulary, and the caller does not have to know which is
 * which.
 *
 * <h2>Neither method throws, and neither blocks</h2>
 * <p>A score is recorded from inside the turn it measures. Every failure mode here — no
 * observation open, a full queue, a Langfuse that is down, a 400 on the body — degrades to
 * a missing score. None of them may cost a user their answer, and none of them may spend
 * the caller's latency budget: the triage judge exists because it answers in a 0.91 s
 * median, and a synchronous HTTP round trip would spend that on bookkeeping.
 *
 * <p>The consequence worth stating plainly: <b>calling {@code record} is not a promise that
 * the score arrives.</b> It is a promise that trying cannot hurt the turn.
 */
public interface ScoreWriter {

    /**
     * Records {@code score} against whatever observation is currently open.
     *
     * <p>With nothing open — tracing switched off, or a thread that is outside a turn —
     * the score is dropped and logged at debug. Debug rather than warn because "no
     * observation is open" is the normal, expected state of a deployment that has not
     * configured tracing, and a warning there would fire on every turn of every run.
     */
    void record(Score score);

    /**
     * Records {@code score} against an observation named explicitly, for the caller that
     * finished a piece of work, closed its observation, and only then learned how good it
     * was — an evaluator that grades a completed turn, for instance.
     *
     * <p>The Scores API lets a score name exactly one subject, and an {@code observationId}
     * is only meaningful with the {@code traceId} it lives under, which is why
     * {@link ObservationRef} carries the pair rather than either half alone.
     */
    void record(ObservationRef target, Score score);
}
