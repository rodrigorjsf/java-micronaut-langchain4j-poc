package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

/**
 * Which deployment produced a trace: the three constants Langfuse filters releases and
 * environments by.
 *
 * <p>Set on every span rather than only on the OpenTelemetry {@code Resource}. Resource
 * attributes land under {@code metadata.resourceAttributes} in Langfuse, which is a
 * catch-all and is not filterable — the very thing these three are for.
 */
@Singleton
public class DeploymentIdentity {

    private final String environment;
    private final String version;
    private final String release;

    public DeploymentIdentity(
            @Value("${agentic.observability.environment:default}") String environment,
            @Value("${agentic.observability.version:${micronaut.application.version:0.1}}") String version,
            @Value("${agentic.observability.release:}") String release) {
        this.environment = environment;
        this.version = version;
        this.release = release;
    }

    public String environment() {
        return environment;
    }

    public String version() {
        return version;
    }

    public String release() {
        return release;
    }
}
