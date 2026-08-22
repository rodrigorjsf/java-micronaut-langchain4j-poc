package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ScoreWriter} a deployment gets when no Langfuse credentials are configured.
 *
 * <p>It exists so that {@code ScoreWriter} is always injectable and the calling code never
 * has a {@code @Nullable} dependency or an {@code if} around a score. Spans already work
 * this way for free — with no exporter configured OpenTelemetry hands back its own no-op
 * {@link io.opentelemetry.api.trace.Tracer} — but the Scores API has no SDK behind it to
 * supply that, so this class is the equivalent.
 *
 * <p>Selected by absence, the same way {@code OtelAgentTracer} claims {@code AgentTracer}:
 * {@link LangfuseScoreWriter} is gated on the three Langfuse properties, so when they are
 * set it is the {@code ScoreWriter} and this bean is not created, and when they are not,
 * this one is the only candidate. Both directions are asserted rather than assumed —
 * getting it wrong yields either a {@code NonUniqueBeanException} at startup or, worse, a
 * silent no-op in a deployment that paid for a Langfuse instance.
 *
 * <p>This is what keeps the default build honest: no credentials, no queue, no thread, no
 * socket, and a test suite that needs no network.
 */
@Singleton
@Requires(missingBeans = ScoreWriter.class)
public class NoOpScoreWriter implements ScoreWriter {

    private static final Logger LOG = LoggerFactory.getLogger(NoOpScoreWriter.class);

    public NoOpScoreWriter() {
        // Once, at startup, and at INFO. Somebody who has configured evaluations and sees
        // no scores in Langfuse should be able to find the reason in the log rather than
        // in this class — the same reason CostCalculator announces which models it priced.
        LOG.info("Langfuse credentials are not configured; scores are discarded");
    }

    @Override
    public void record(Score score) {
        LOG.debug("Discarding score '{}': no Langfuse configured", name(score));
    }

    @Override
    public void record(ObservationRef target, Score score) {
        LOG.debug("Discarding score '{}': no Langfuse configured", name(score));
    }

    private static String name(Score score) {
        return score == null ? "<null>" : score.name();
    }
}
