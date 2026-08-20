package io.github.rodrigorjsf.agenticchat.infra.valkey;

import io.github.rodrigorjsf.agenticchat.infra.config.ValkeyProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.annotation.Bean;
import io.github.rodrigorjsf.agenticchat.infra.aws.LocalAwsBootstrap;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

/**
 * One Lettuce client and one multiplexed connection for the whole application.
 *
 * <p>A single connection, not a pool: a Lettuce {@code StatefulRedisConnection} is
 * thread-safe and pipelines commands, so it serves thousands of virtual threads
 * without contention. A pool would add moving parts without adding throughput. The
 * exception — blocking commands such as {@code BLPOP} and {@code MULTI/EXEC} — needs
 * its own connection, and nothing here uses them.
 *
 * <p>{@code preDestroy} is explicit on both beans. Lettuce holds Netty event-loop
 * threads and an open socket; without ordered shutdown a rolling restart drops
 * in-flight commands and surfaces as 5xx during deploys.
 */
@Factory
public class ValkeyFactory {

    /**
     * @param bootstrap present only to order construction, never called.
     *                  {@code LocalAwsBootstrap} creates the ElastiCache replication
     *                  group this client dials, and Micronaut orders two beans only
     *                  when one is a parameter of the other — so without this
     *                  parameter the ordering was a claim in a javadoc and nothing
     *                  more, and {@code connect()} could win the race and fail the
     *                  boot intermittently. Absent unless bootstrap is enabled.
     */
    @Singleton
    @Bean(preDestroy = "shutdown")
    RedisClient redisClient(ValkeyProperties props,
                            @Nullable LocalAwsBootstrap bootstrap) {
        var uri = RedisURI.builder()
                .withHost(props.host())
                .withPort(props.port())
                .withTimeout(props.commandTimeout())
                .build();
        return RedisClient.create(uri);
    }

    @Singleton
    @Bean(preDestroy = "close")
    StatefulRedisConnection<String, String> valkeyConnection(RedisClient client) {
        return client.connect();
    }
}
