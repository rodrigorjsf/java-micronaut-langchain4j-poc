package io.github.rodrigorjsf.agenticchat.infra;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.time.Clock;

/**
 * A single injectable {@link Clock}. Nothing in this codebase calls
 * {@code Instant.now()} directly, so TTLs, cost windows and rate limits are all
 * testable without sleeping.
 */
@Factory
public class ClockFactory {

    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}
