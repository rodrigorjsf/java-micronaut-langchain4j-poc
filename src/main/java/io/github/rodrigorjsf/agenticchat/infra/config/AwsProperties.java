package io.github.rodrigorjsf.agenticchat.infra.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.AccessorsStyle;
import io.micronaut.core.bind.annotation.Bindable;

/**
 * Where the AWS-shaped services live. Locally this points at floci; in a real
 * deployment the endpoint is left unset and the SDK resolves the real AWS one.
 *
 * <p>{@code @AccessorsStyle(readPrefixes = "")} is what allows the record-style
 * {@code endpoint()} naming — a {@code @ConfigurationProperties} interface
 * otherwise demands JavaBean {@code getX()} accessors and fails at compile time
 * with "Method format unrecognized for @ConfigurationProperties interfaces".
 */
@ConfigurationProperties("agentic.aws")
@AccessorsStyle(readPrefixes = "")
public interface AwsProperties {

    @Bindable(defaultValue = "http://localhost:4566")
    String endpoint();

    @Bindable(defaultValue = "us-east-1")
    String region();

    @Bindable(defaultValue = "test")
    String accessKeyId();

    @Bindable(defaultValue = "test")
    String secretAccessKey();
}
