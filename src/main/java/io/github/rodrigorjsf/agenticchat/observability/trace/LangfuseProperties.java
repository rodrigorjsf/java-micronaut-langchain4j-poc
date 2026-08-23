package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;

/**
 * Where a Langfuse instance is and how to authenticate to it.
 *
 * <p>Absent by default. Configuring an exporter that nobody asked for would have the default
 * build — which needs no network, no Docker and no API key — opening a connection on every run.
 */
@ConfigurationProperties(LangfuseProperties.PREFIX)
public class LangfuseProperties {

    public static final String PREFIX = "agentic.observability.langfuse";

    private String host;
    private String publicKey;
    private String secretKey;

    @Nullable
    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    @Nullable
    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    @Nullable
    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
}
