package io.github.rodrigorjsf.agenticchat.infra.valkey;

import io.github.rodrigorjsf.agenticchat.infra.config.ValkeyProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/**
 * One Lettuce client and one multiplexed connection for the whole application.
 * Lettuce connections are thread-safe and pipeline commands, so a pool would add
 * moving parts without adding throughput.
 */
@Factory
public class ValkeyFactory {

    @Singleton
    RedisClient redisClient(ValkeyProperties props) {
        var uri = RedisURI.builder()
                .withHost(props.host())
                .withPort(props.port())
                .withTimeout(props.commandTimeout())
                .build();
        return RedisClient.create(uri);
    }

    @Singleton
    StatefulRedisConnection<String, String> valkeyConnection(RedisClient client) {
        return client.connect();
    }
}
