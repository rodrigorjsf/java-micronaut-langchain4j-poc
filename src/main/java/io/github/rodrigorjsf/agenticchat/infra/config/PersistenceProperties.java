package io.github.rodrigorjsf.agenticchat.infra.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.AccessorsStyle;
import io.micronaut.core.bind.annotation.Bindable;

import java.time.Duration;

/**
 * Table names and retention for everything the chat persists.
 */
@ConfigurationProperties("agentic.persistence")
@AccessorsStyle(readPrefixes = "")
public interface PersistenceProperties {

    /**
     * Single DynamoDB table; every entity is discriminated by its pk/sk prefix.
     */
    @Bindable(defaultValue = "agentic_chat")
    String tableName();

    /**
     * Written to the DynamoDB TTL attribute and used as the Valkey key expiry.
     */
    @Bindable(defaultValue = "PT24H")
    Duration conversationTtl();

    /**
     * Create the DynamoDB table and the ElastiCache replication group on startup.
     *
     * <p>Off by default: provisioning infrastructure is not something an application
     * should do unless told to. The local and docker environments turn it on because
     * floci is a disposable emulator; against real AWS the infrastructure is
     * provisioned outside the application and this stays off.
     */
    @Bindable(defaultValue = "false")
    boolean bootstrapEnabled();
}
