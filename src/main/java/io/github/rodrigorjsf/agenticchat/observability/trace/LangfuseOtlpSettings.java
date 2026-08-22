package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * The OTLP wire settings for a Langfuse instance.
 *
 * <p>Its own class because each of these three values fails silently when it is wrong, in a
 * different way, and none of them is checked by anything at startup:
 *
 * <ul>
 *   <li>the <b>path</b> is the signal-specific one. Langfuse documents {@code /api/public/otel} for
 *       exporters that append the signal themselves and {@code /api/public/otel/v1/traces} for
 *       those that do not. The OpenTelemetry Java OTLP HTTP exporter takes a complete endpoint,
 *       so this is the one to use; the base path answers 4xx from a background thread nobody
 *       reads;</li>
 *   <li>the <b>credentials</b> are HTTP Basic over {@code publicKey:secretKey}, not a bearer
 *       token;</li>
 *   <li>the <b>ingestion header</b> selects the v4 path. Without it, Langfuse's own
 *       documentation says directly-ingested OpenTelemetry data "can be delayed by up to 10
 *       minutes" — which does not look like a misconfiguration, it looks like nothing is
 *       happening.</li>
 * </ul>
 *
 * <p>gRPC is not an option: Langfuse supports OTLP over HTTP only, in either {@code HTTP/JSON} or
 * {@code HTTP/protobuf}.
 */
@Singleton
// pattern rather than bare presence: a property set to an empty string is present, and
// an exporter built on an empty endpoint fails the SDK at startup rather than degrading.
@Requires(property = LangfuseProperties.PREFIX + ".host", pattern = "\\S+")
@Requires(property = LangfuseProperties.PREFIX + ".public-key", pattern = "\\S+")
@Requires(property = LangfuseProperties.PREFIX + ".secret-key", pattern = "\\S+")
public class LangfuseOtlpSettings {

    private static final String TRACES_PATH = "/api/public/otel/v1/traces";

    private final String tracesEndpoint;
    private final Map<String, String> headers;

    public LangfuseOtlpSettings(LangfuseProperties properties) {
        String host = properties.getHost().endsWith("/")
                ? properties.getHost().substring(0, properties.getHost().length() - 1)
                : properties.getHost();
        this.tracesEndpoint = host + TRACES_PATH;
        String credentials = properties.getPublicKey() + ":" + properties.getSecretKey();
        this.headers = Map.of(
                "Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)),
                "x-langfuse-ingestion-version", "4");
    }

    public String tracesEndpoint() {
        return tracesEndpoint;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
