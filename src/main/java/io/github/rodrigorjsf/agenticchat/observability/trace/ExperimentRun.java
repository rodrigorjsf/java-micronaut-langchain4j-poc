package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.opentelemetry.context.Context;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs the rows of a labelled dataset as one Langfuse experiment.
 *
 * <p>Everything here is spelled with the public {@link AgentTracer} and
 * {@link ExperimentContext} vocabulary, so an eval that wanted to could do the same by
 * hand. It exists because two of the steps are invisible when they are wrong, and both
 * are wrong by default.
 *
 * <h2>Trap 1 — the item root must be the OpenTelemetry TRACE root</h2>
 * <p>Langfuse expects one trace per experiment item and identifies the item by that
 * trace's root span. Any ambient context adopts the item span — a JUnit-level span, a
 * surrounding turn, a caller that forgot to close something — and then
 * {@code langfuse.experiment.item.root_observation_id} names a span in a different trace.
 * The export is well-formed, the ingestion succeeds, and the run is simply wrong.
 * {@link Context#root()} is what detaches it.
 *
 * <p><b>And detaching drops the experiment too.</b> {@link ExperimentContext} keeps its
 * attributes under a {@code ContextKey} on the very {@link Context} being discarded, so
 * the run has to be re-opened on top of the root context. Skip that and
 * {@link ExperimentContext#openItem} finds nothing, returns its no-op scope, and every
 * span in the run exports with no {@code langfuse.experiment.*} attribute at all — which
 * looks, in the UI, exactly like an experiment nobody started.
 *
 * <h2>Trap 2 — the item identity must reach the item's CHILD spans</h2>
 * <p>Langfuse v4 queries observations, not traces. An item id written only on the root
 * leaves every model call, tool call and guardrail inside that row unattributable to the
 * row, and the run appears to contain exactly one observation per item.
 *
 * <h2>Two things deliberately absent</h2>
 * <p><b>No {@code AutoCloseable}.</b> Each item opens and closes its own experiment scope,
 * because it has to re-open one after the detach anyway, so a run-level scope would cover
 * only the gaps between items — nothing this class can observe. A {@code close()} that
 * closes dead weight is a lifecycle to get wrong for no return.
 *
 * <p><b>No {@link Observation#asTraceRoot()} on the item root.</b> Those two attributes
 * exist for the case where the application's root is not the OpenTelemetry trace root.
 * After the detach above it is, so Langfuse derives the same thing from the span itself.
 *
 * <p>Contract read 2026-08-23 from
 * {@code https://langfuse.com/integrations/native/opentelemetry/experiments} and
 * {@code https://langfuse.com/docs/observability/features/corrections}.
 */
public final class ExperimentRun {

    private final AgentTracer tracer;
    private final ScoreWriter scores;
    private final ObservationContentPolicy content;
    private final ExperimentAttributes experiment;

    private ExperimentRun(AgentTracer tracer, ScoreWriter scores,
                          ObservationContentPolicy content, ExperimentAttributes experiment) {
        this.tracer = tracer;
        this.scores = scores;
        this.content = content;
        this.experiment = experiment;
    }

    /**
     * @param experiment the identity every item in this run shares. Give it a STABLE id
     *                   derived from the dataset rather than a fresh one per run: two
     *                   passes over the same golden set are only comparable if Langfuse
     *                   groups them.
     */
    public static ExperimentRun start(AgentTracer tracer, ScoreWriter scores,
                                      ObservationContentPolicy content, ExperimentAttributes experiment) {
        return new ExperimentRun(tracer, scores, content, experiment);
    }

    /**
     * Runs one row: its own trace, its own item identity, its own grade.
     *
     * <p>{@code work} runs inside the item context, so anything it traces belongs to the
     * row. An exception from it is recorded on the item and rethrown unchanged — the eval
     * decides whether a throw is a rate limit to skip or a defect to fail on, and this
     * class is in no position to.
     *
     * <p><b>All three content attributes go through {@link ObservationContentPolicy},</b>
     * like every other span writer in this application and unlike the two that document an
     * exemption ({@code LangfuseRetrieverListener}, {@code LangfuseEmbeddingModelListener}
     * — both write scores and dimensions, not text). A golden set is normally assembled
     * from production traces, which makes an item's input and output a user's message and
     * a model's reply; with {@code agentic.observability.capture-content=false} they must
     * not export from here while the generation, the guardrail and the tool call in the
     * very same trace correctly write nothing. No ArchUnit rule catches that, and this is
     * the one class an operator has no reason to suspect of it.
     *
     * <p>The item's IDENTITY is not content and is never gated: with capture off a row
     * still exports {@code langfuse.experiment.item.id} and its root observation id, so the
     * run keeps its shape and loses only its text. Gating those would empty the run rather
     * than protect it.
     *
     * @param itemId         the row, stable across runs so Langfuse can compare them
     * @param input          what the row fed in, written as JSON — subject to the policy
     * @param expectedOutput the label. This is also what a failed row is corrected with,
     *                       which is why it is a {@code String}: a Langfuse correction is
     *                       a string score, and no other shape survives the round trip.
     *                       The span attribute is policy-gated; the correction is not —
     *                       see {@link #grade} for where that line is drawn
     * @param work           the row's actual work
     * @param passed         whether what {@code work} produced counts as correct
     * @return whatever {@code work} produced, so the caller can report on it too
     */
    public <T> T item(String itemId, Object input, String expectedOutput, Supplier<T> work, Predicate<T> passed) {
        // Trap 1, both halves. See the class comment for what each one costs.
        try (var detached = Context.root().makeCurrent();
             var run = ExperimentContext.open(experiment);
             var root = tracer.start("experiment-item", ObservationType.AGENT)) {
            root.input(content.capture(input))
                    .experimentItem(itemId, content.capture(expectedOutput));
            // Trap 2. Null version: itemVersion names a Langfuse-managed dataset revision,
            // and these rows are files in this repository.
            try (var item = ExperimentContext.openItem(root.ref().observationId(), itemId, null)) {
                T produced = work.get();
                root.output(content.capture(produced));
                grade(root, expectedOutput, passed.test(produced));
                return produced;
            } catch (RuntimeException e) {
                // An item that ends with neither an output nor an error reads in Langfuse
                // as a row that ran and produced nothing.
                root.failed(e);
                throw e;
            }
        }
    }

    /**
     * Grades the row from inside an observation typed
     * {@link ObservationType#EVALUATOR} — Langfuse's own definition of that type is "a
     * function that assesses another component's output", which is what an eval assertion
     * is. It also earns the trace an agent graph, which Langfuse draws only when a trace
     * holds a type other than span, event or generation.
     *
     * <p><b>The evaluator is the step; the item root is the subject.</b> Both scores name
     * {@code root} explicitly, because the OpenTelemetry-experiments contract says an
     * experiment-item score is filed with "the root span's traceId as traceId and its
     * spanId as observationId" — and the no-target {@code record} overload resolves
     * against whatever is CURRENT, which inside this try-with-resources is the
     * {@code grade} child. That mistake ingests cleanly and shows a score in the UI; what
     * it empties is the run's pass-rate column, because the verdict is the only score a
     * passing row files and a healthy run is mostly passing rows.
     *
     * <p>The correction is <em>not</em> passed through {@link ObservationContentPolicy},
     * unlike the three span attributes {@link #item} writes. It carries the dataset's own
     * committed label — the shipped golden set — over the Scores API, not text the turn
     * produced, and gating it would silently disable the Corrections feature on exactly
     * the deployments that most need to see which rows regressed.
     */
    private void grade(Observation root, String expectedOutput, boolean passed) {
        try (var evaluator = tracer.start("grade", ObservationType.EVALUATOR)) {
            scores.record(root.ref(), Score.bool("correct", passed));
            if (!passed && expectedOutput != null && !expectedOutput.isBlank()) {
                // Only on a row that failed. A correction on a passing row is a
                // fine-tuning example asserting that the right answer was the wrong one.
                scores.record(root.ref(), Score.correction(expectedOutput));
            }
        }
    }
}
