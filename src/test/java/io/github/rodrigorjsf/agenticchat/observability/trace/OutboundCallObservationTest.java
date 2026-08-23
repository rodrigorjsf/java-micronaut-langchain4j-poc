package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.github.rodrigorjsf.agenticchat.tools.http.ToolHttpClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one hop in this application that OpenTelemetry could lose: an outbound tool call.
 *
 * <p>Every LangChain4j seam runs on the caller's thread, so the context is simply there.
 * {@link ToolHttpClient} is different — it goes through a Reactor {@code Mono} and the Micronaut
 * HTTP client answers on a Netty event loop, which is a different thread from the one that opened
 * the observation. If the context does not cross that boundary the outbound span becomes the root
 * of its own trace, and a tool call that took four seconds is invisible in the turn that made it.
 *
 * <p>This is measured rather than assumed, because the failure is silent: both traces exist, both
 * look plausible, and nothing reports an error.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboundCallObservationTest {

    private EmbeddedServer upstream;
    private ApplicationContext ctx;
    private InMemorySpanExporter exported;
    private ToolHttpClient tools;
    private AgentTracer tracer;

    @BeforeAll
    void startUpstreamAndClient() {
        upstream = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "stub.api.enabled", "true",
                "micronaut.server.port", -1));

        Map<String, Object> config = new HashMap<>(Map.of(
                "agentic.test.record-spans", "true",
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake",
                "agentic.tools.apis.stub.base-url", "http://localhost:" + upstream.getPort() + "/stub",
                "agentic.tools.apis.stub.timeout", "PT2S",
                "agentic.tools.apis.stub.max-retries", 0,
                "agentic.tools.apis.stub.max-response-bytes", 4096));
        config.put("agentic.guardrails.output.extra-allowed-link-hosts", List.of("wikipedia.org"));

        ctx = ApplicationContext.run(config);
        exported = ctx.getBean(InMemorySpanExporter.class);
        tools = ctx.getBean(ToolHttpClient.class);
        tracer = ctx.getBean(AgentTracer.class);
    }

    @AfterAll
    void stop() {
        if (ctx != null) {
            ctx.close();
        }
        if (upstream != null) {
            upstream.close();
        }
    }

    @Test
    @DisplayName("an outbound tool call is a child of the observation that made it, not a new trace")
    void theOutboundSpanStaysInTheTrace() {
        exported.reset();

        String toolSpanId;
        try (var toolObservation = tracer.start("stub-tool", ObservationType.TOOL)) {
            toolSpanId = toolObservation.ref().observationId();
            tools.get("stub", "/ok", Map.of());
        }

        var spans = exported.getFinishedSpanItems();
        var tool = named(spans, "stub-tool");
        var outbound = spans.stream()
                .filter(span -> !span.getName().equals("stub-tool"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the Micronaut HTTP client produced no span; exported: "
                                + spans.stream().map(SpanData::getName).toList()));

        assertThat(outbound.getTraceId()).isEqualTo(tool.getTraceId());
        assertThat(outbound.getParentSpanId()).isEqualTo(toolSpanId);
    }

    private static SpanData named(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> span.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name));
    }
}
