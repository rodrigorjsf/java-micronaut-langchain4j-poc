package io.github.rodrigorjsf.agenticchat.observability.trace;

/**
 * Langfuse's severity scale for a single observation, used to filter the noise out of
 * a trace that has a lot of them.
 *
 * <p>Read 2026-08-22 from {@code https://langfuse.com/docs/observability/features/log-levels}.
 */
public enum ObservationLevel {
    DEBUG, DEFAULT, WARNING, ERROR
}
