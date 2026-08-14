package io.github.rodrigorjsf.agenticchat.infra.aws;

import io.github.rodrigorjsf.agenticchat.infra.config.PersistenceProperties;
import io.github.rodrigorjsf.agenticchat.infra.config.ValkeyProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.ElastiCacheException;

/**
 * Creates the local infrastructure the app expects, idempotently, before anything
 * connects to it.
 *
 * <p>{@code @Context} (not {@code @Singleton}) so it is instantiated eagerly at
 * startup: the Valkey client factory declares a dependency on this bean, which is
 * what guarantees the ElastiCache replication group exists before Lettuce dials it.
 *
 * <p>floci quirk that this class exists to absorb: {@code CreateCacheCluster} is
 * memcached-only — Valkey and Redis engines must be created with
 * {@code CreateReplicationGroup}, and floci then proxies RESP on its own port.
 */
@Context
@Requires(property = "agentic.persistence.bootstrap-enabled", value = "true")
public class LocalAwsBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(LocalAwsBootstrap.class);

    private final DynamoDbClient dynamoDb;
    private final ElastiCacheClient elastiCache;
    private final PersistenceProperties persistence;
    private final ValkeyProperties valkey;

    public LocalAwsBootstrap(DynamoDbClient dynamoDb,
                             ElastiCacheClient elastiCache,
                             PersistenceProperties persistence,
                             ValkeyProperties valkey) {
        this.dynamoDb = dynamoDb;
        this.elastiCache = elastiCache;
        this.persistence = persistence;
        this.valkey = valkey;
    }

    @PostConstruct
    void bootstrap() {
        createTable();
        enableTtl();
        createCache();
    }

    private void createTable() {
        var name = persistence.tableName();
        try {
            dynamoDb.createTable(b -> b
                    .tableName(name)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                    .keySchema(
                            KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
            LOG.info("Created DynamoDB table {}", name);
        } catch (ResourceInUseException alreadyThere) {
            LOG.debug("DynamoDB table {} already exists", name);
        }
    }

    private void enableTtl() {
        try {
            dynamoDb.updateTimeToLive(b -> b
                    .tableName(persistence.tableName())
                    .timeToLiveSpecification(TimeToLiveSpecification.builder()
                            .enabled(true)
                            .attributeName("expires_at")
                            .build()));
        } catch (RuntimeException e) {
            // Re-enabling an already-enabled TTL is an error on real AWS and a no-op
            // on some emulators. Neither is worth failing startup over.
            LOG.debug("TTL already configured on {}: {}", persistence.tableName(), e.getMessage());
        }
    }

    private void createCache() {
        var groupId = valkey.replicationGroupId();
        try {
            var response = elastiCache.createReplicationGroup(b -> b
                    .replicationGroupId(groupId)
                    .replicationGroupDescription("agentic chat hot memory")
                    .engine("valkey")
                    .cacheNodeType("cache.t3.micro")
                    .numCacheClusters(1));
            LOG.info("Created ElastiCache replication group {} at {}", groupId,
                    response.replicationGroup().configurationEndpoint());
        } catch (ElastiCacheException e) {
            LOG.debug("ElastiCache replication group {} already exists or is unavailable: {}",
                    groupId, e.getMessage());
        }
    }
}
