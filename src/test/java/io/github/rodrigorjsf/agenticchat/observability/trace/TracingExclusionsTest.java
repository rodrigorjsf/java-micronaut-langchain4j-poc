package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.ApplicationContext;
import io.micronaut.tracing.opentelemetry.instrument.util.OpenTelemetryExclusionsConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which HTTP routes never become a span at all.
 *
 * <p>This is the one place where a configuration list is load-bearing enough to test.
 * Micronaut's server filter turns every request into a root span, and a root span is what
 * Langfuse turns into a TRACE — so a liveness probe polled every few seconds is not noise
 * beside the real traffic, it BECOMES the traffic. Measured on this project's own
 * instance: of 37 observations the containerised application had ever sent to Langfuse,
 * 37 were {@code GET /health} and none was a conversation.
 *
 * <p>The exclusion belongs here rather than in the collector's config, and that is the
 * decision this test pins. A {@code filter} processor in the collector would clean up the
 * Grafana leg only: the application exports to Langfuse DIRECTLY, so a probe span dropped
 * at the collector still arrives at Langfuse. Excluding it at the source is the only place
 * that covers both readers.
 *
 * <p>The predicate is built by the framework, from this application's own YAML, and it
 * matches with {@link java.util.regex.Matcher#matches()} — a FULL match. That is the trap
 * the last two assertions exist for: {@code /health} as a pattern does not exclude
 * {@code /health/liveness}, so a list that looks complete silently keeps exporting the
 * probes a Kubernetes deployment actually sends.
 */
class TracingExclusionsTest {

    private ApplicationContext ctx;
    private Predicate<String> excluded;

    @BeforeEach
    void setUp() {
        ctx = ApplicationContext.run(Map.of(
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake",
                "agentic.test.stub-models", "true"));
        var configuration = ctx.getBean(OpenTelemetryExclusionsConfiguration.class);
        excluded = configuration.exclusionTest();
        assertThat(excluded).as("otel.exclusions is configured at all").isNotNull();
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Test
    @DisplayName("the health endpoint and its sub-paths produce no span")
    void healthIsNotTraced() {
        assertThat(excluded.test("/health")).isTrue();
        assertThat(excluded.test("/health/liveness")).isTrue();
        assertThat(excluded.test("/health/readiness")).isTrue();
    }

    @Test
    @DisplayName("the metrics scrape produces no span either")
    void theMetricsScrapeIsNotTraced() {
        // Prometheus scrapes this on a fixed interval for as long as the stack is up, so
        // left in it outnumbers real traffic for exactly the same reason /health does.
        assertThat(excluded.test("/prometheus")).isTrue();
    }

    @Test
    @DisplayName("the chat route is excluded so the turn observation can be the trace root")
    void theChatRouteKeepsItsExistingExclusion() {
        // Not new, and not for the same reason: POST /api/chat is excluded so the server
        // filter does not put an HTTP span ABOVE the turn, one level up from the only
        // observation carrying the message and the reply.
        assertThat(excluded.test("/api/chat")).isTrue();
    }

    @Test
    @DisplayName("this application's own calls to Langfuse are not traced back to Langfuse")
    void theScoreWriterIsNotTraced() {
        // The exclusion list feeds the CLIENT filter as well as the server one, which is the
        // only reason this works: LangfuseScoreWriter posts over an instrumented Micronaut
        // HTTP client, and an outbound span with no parent is a root span, which is a trace,
        // which is exported to Langfuse. Measured on a six-turn run against a real instance:
        // 10 traces named `POST`, one per score write, filed beside the conversations.
        assertThat(excluded.test("/api/public/scores")).isTrue();
        assertThat(excluded.test("/api/public/otel/v1/traces")).isTrue();
    }

    @Test
    @DisplayName("nothing a user actually calls is excluded by accident")
    void realRoutesAreStillTraced() {
        // The one real sibling route this application serves today. It keeps its server
        // span: it is ordinary traffic and it has no turn observation to be the root of.
        assertThat(excluded.test("/api/chat/capabilities")).isFalse();
        // Not a route that exists — the assertion is about the PATTERN. `/api/chat` is a
        // full match, so it must not swallow a sub-path that someone adds under the
        // controller later and reasonably expects to see traced.
        assertThat(excluded.test("/api/chat/history")).isFalse();
    }
}
