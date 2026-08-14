package io.github.rodrigorjsf.agenticchat.infra.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.AccessorsStyle;
import io.micronaut.core.bind.annotation.Bindable;

import java.time.Duration;

/**
 * Connection settings for the Valkey (Redis-protocol) hot store.
 *
 * <p>Locally the host/port are floci's ElastiCache auth proxy, not the Valkey
 * container floci spawns: that child container publishes nothing, and floci
 * fronts it on its own port. See
 * {@code docs/adr/0004-floci-as-the-aws-and-cache-substrate.md}.
 */
@ConfigurationProperties("agentic.valkey")
@AccessorsStyle(readPrefixes = "")
public interface ValkeyProperties {

    @Bindable(defaultValue = "localhost")
    String host();

    @Bindable(defaultValue = "6379")
    int port();

    /** ElastiCache replication group that {@code LocalAwsBootstrap} creates on floci. */
    @Bindable(defaultValue = "agentic-chat-cache")
    String replicationGroupId();

    @Bindable(defaultValue = "PT10S")
    Duration commandTimeout();
}
