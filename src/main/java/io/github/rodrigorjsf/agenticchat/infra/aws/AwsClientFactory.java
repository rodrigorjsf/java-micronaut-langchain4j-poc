package io.github.rodrigorjsf.agenticchat.infra.aws;

import io.github.rodrigorjsf.agenticchat.infra.config.AwsProperties;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;

import java.net.URI;

/**
 * Builds the AWS SDK clients. Only the endpoint differs between floci and real
 * AWS, so the same code path serves both — that is the point of using an
 * emulator rather than mocks.
 */
@Factory
public class AwsClientFactory {

    private final AwsProperties props;

    public AwsClientFactory(AwsProperties props) {
        this.props = props;
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKeyId(), props.secretAccessKey()));
    }

    @Singleton
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(credentials())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Singleton
    public ElastiCacheClient elastiCacheClient() {
        return ElastiCacheClient.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(credentials())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
