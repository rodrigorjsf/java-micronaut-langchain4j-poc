package io.github.rodrigorjsf.agenticchat.testsupport;

import io.github.rodrigorjsf.agenticchat.observability.trace.ObservationRef;
import io.github.rodrigorjsf.agenticchat.observability.trace.Score;
import io.github.rodrigorjsf.agenticchat.observability.trace.ScoreWriter;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures the scores the pipeline emits, with the observation each was attached to.
 *
 * <p>Concurrent because the trip-briefing workflow runs sub-agents on several threads and a
 * plain {@code ArrayList} loses an entry when two of them record at once — which reads as a
 * score that was never emitted rather than as a race.
 */
@Singleton
@Requires(property = "agentic.test.record-scores", value = "true")
public class RecordingScoreWriter implements ScoreWriter {

    public record Recorded(ObservationRef target, Score score) {
    }

    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();

    @Override
    public void record(Score score) {
        recorded.add(new Recorded(null, score));
    }

    @Override
    public void record(ObservationRef target, Score score) {
        recorded.add(new Recorded(target, score));
    }

    public List<Recorded> recorded() {
        return List.copyOf(recorded);
    }

    public List<Score> scoresNamed(String name) {
        return recorded.stream().map(Recorded::score).filter(score -> score.name().equals(name)).toList();
    }

    public void reset() {
        recorded.clear();
    }
}
