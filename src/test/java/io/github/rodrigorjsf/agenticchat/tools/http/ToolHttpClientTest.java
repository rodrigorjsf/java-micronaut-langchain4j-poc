package io.github.rodrigorjsf.agenticchat.tools.http;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolHttpClientTest {

    private EmbeddedServer server;
    private ApplicationContext client;
    private ToolHttpClient tools;

    @BeforeAll
    void startStubAndClient() {
        server = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "stub.api.enabled", "true",
                "micronaut.server.port", -1));

        var base = "http://localhost:" + server.getPort() + "/stub";
        Map<String, Object> config = new HashMap<>(Map.of(
                "agentic.tools.apis.stub.base-url", base,
                "agentic.tools.apis.stub.timeout", "PT2S",
                "agentic.tools.apis.stub.max-retries", 1,
                "agentic.tools.apis.stub.max-response-bytes", 1024,
                "agentic.tools.apis.stub.user-agent", "agentic-chat-poc/0.1 (test)",
                "agentic.tools.apis.slowapi.base-url", base,
                "agentic.tools.apis.slowapi.timeout", "PT1S",
                "agentic.tools.apis.slowapi.max-retries", 0,
                "agentic.tools.user-agent", "agentic-chat-poc/0.1 (default)"));
        client = ApplicationContext.run(config);
        tools = client.getBean(ToolHttpClient.class);
    }

    @AfterAll
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void resetCounters() {
        StubApiController.FLAKY_CALLS.set(0);
    }

    @Test
    void returnsTheBodyOnSuccess() {
        var response = tools.get("stub", "/ok");

        assertThat(response.isOk()).isTrue();
        assertThat(response.truncated()).isFalse();
        assertThat(response.body()).contains("Sao Paulo");
        assertThat(response.toModelText()).isEqualTo(response.body());
    }

    @Test
    void encodesQueryParametersInsteadOfConcatenatingThem() {
        var response = tools.get("stub", "/echo-query", Map.of("q", "rua da paz & cia"));

        assertThat(response.isOk()).isTrue();
        assertThat(response.body()).contains("rua da paz & cia");
    }

    @Test
    @DisplayName("an endpoint with no User-Agent still gets the shared default")
    void fallsBackToTheSharedUserAgent() {
        // Java's default UA is the shape Wikimedia's bot policy rejects outright, so
        // "no User-Agent configured" must never mean "no User-Agent sent".
        assertThat(tools.get("slowapi", "/echo-agent").body()).contains("agentic-chat-poc/0.1 (default)");
    }

    @Test
    void sendsTheConfiguredUserAgent() {
        // Nominatim, Crossref and Wikimedia answer 403 without one.
        assertThat(tools.get("stub", "/echo-agent").body()).contains("agentic-chat-poc/0.1 (test)");
    }

    @Test
    void skipsBlankQueryParameters() {
        var response = tools.get("stub", "/ok", Map.of("q", "", "other", "  "));

        assertThat(response.isOk()).isTrue();
    }

    @Test
    void truncatesToTheEndpointBudgetAndSaysSo() {
        var response = tools.get("stub", "/big", Map.of("size", "5000"));

        assertThat(response.isOk()).isTrue();
        assertThat(response.truncated()).isTrue();
        assertThat(response.body().length()).isLessThanOrEqualTo(1024);
        assertThat(response.toModelText())
                .as("the model must be told the result is partial, not silently handed a fragment")
                .contains("truncated");
    }

    @Test
    void mapsNotFoundToAnAnswerRatherThanAnError() {
        var response = tools.get("stub", "/missing");

        assertThat(response.outcome()).isEqualTo(ToolResponse.Outcome.NOT_FOUND);
        assertThat(response.toModelText()).contains("do not guess");
    }

    @Test
    void mapsBadRequestToSomethingTheModelCanFix() {
        var response = tools.get("stub", "/bad-args");

        assertThat(response.outcome()).isEqualTo(ToolResponse.Outcome.INVALID_REQUEST);
        assertThat(response.toModelText()).contains("Correct the arguments");
    }

    @Test
    void doesNotRetryAThrottledCall() {
        var response = tools.get("stub", "/throttled");

        assertThat(response.outcome()).isEqualTo(ToolResponse.Outcome.RATE_LIMITED);
        assertThat(response.toModelText()).contains("Do not retry");
    }

    @Test
    void neverLeaksTheUpstreamErrorBodyToTheModel() {
        var response = tools.get("stub", "/broken");

        assertThat(response.outcome()).isEqualTo(ToolResponse.Outcome.UPSTREAM_ERROR);
        assertThat(response.toModelText())
                .as("a 5xx body can carry paths, keys and internal hostnames")
                .doesNotContain("/var/secrets")
                .doesNotContain("boom");
    }

    @Test
    void retriesATransientServerErrorOnce() {
        var response = tools.get("stub", "/flaky");

        assertThat(response.isOk()).isTrue();
        assertThat(response.body()).contains("recovered");
        assertThat(StubApiController.FLAKY_CALLS.get()).isEqualTo(2);
    }

    @Test
    void appliesThePerEndpointTimeout() {
        long start = System.nanoTime();
        var response = tools.get("slowapi", "/slow");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.outcome()).isEqualTo(ToolResponse.Outcome.UPSTREAM_ERROR);
        assertThat(elapsedMs)
                .as("the 1 s endpoint budget must win over the stub's 3 s delay")
                .isLessThan(2_500);
    }

    @Test
    void refusesAnApiThatIsNotInTheCatalogue() {
        // A tool cannot reach a host that was never configured — this is the SSRF control.
        var response = tools.get("http://169.254.169.254", "/latest/meta-data/");

        assertThat(response.outcome()).isEqualTo(ToolResponse.Outcome.UPSTREAM_ERROR);
        assertThat(response.toModelText()).contains("not configured");
    }

    @Test
    void exposesTheCatalogueForStartupValidation() {
        assertThat(tools.knownApis()).contains("stub", "slowapi");
    }
}
