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
             * Hard ceiling on what a single tool call can pull into the model's
             * context. Public APIs return fat JSON; 32 KB of it is roughly 8k
             * tokens, which is already more than any single tool result should
             * cost. Truncation is reported to the model rather than hidden.
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
