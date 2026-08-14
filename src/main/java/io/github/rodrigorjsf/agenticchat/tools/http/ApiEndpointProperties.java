package io.github.rodrigorjsf.agenticchat.tools.http;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;

import java.time.Duration;

/**
 * One upstream API the tool layer is allowed to call.
 *
 * <p>This is the SSRF control, and it is a design control rather than a filter:
 * no tool ever receives or constructs a URL. A tool names a catalogue entry and a
 * path, and {@link ToolHttpClient} resolves the base URL from here. A model that
 * hallucinates {@code http://169.254.169.254/} has nowhere to put it — there is no
 * parameter that accepts a host.
 */
@EachProperty("agentic.tools.apis")
public class ApiEndpointProperties {

    private final String name;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final int maxResponseBytes;
    private final String userAgent;

    @ConfigurationInject
    public ApiEndpointProperties(
            @Parameter String name,
            String baseUrl,
            @Bindable(defaultValue = "PT6S") Duration timeout,
            @Bindable(defaultValue = "1") int maxRetries,
            /*
             * TRANSPORT ceiling: how many bytes this endpoint may return to us. It
             * is NOT the context ceiling — a tool that projects reduces the body
             * further before the model sees it, and projection needs a complete
             * JSON document to work on.
             *
             * So for a projecting endpoint this must sit ABOVE the raw body, not at
             * the size you want the model to see. Set it below and the transport cut
             * lands mid-object, projection cannot parse it, and the tool answers
             * "narrow your query" for a request that would have worked.
             *
             * For a non-projecting tool the two ceilings coincide, and 32 KB is
             * roughly 8k tokens — already more than any single result should cost.
             */
            @Bindable(defaultValue = "32768") int maxResponseBytes,
            /*
             * Nominatim, Crossref and the Wikimedia APIs require an identifying
             * User-Agent and answer 403 without one.
             */
            @Nullable String userAgent) {
        this.name = name;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.maxResponseBytes = maxResponseBytes;
        this.userAgent = userAgent;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String name() {
        return name;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    @Nullable
    public String userAgent() {
        return userAgent;
    }
}
