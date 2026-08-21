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
        // The link policy is derived from the catalogue, and this catalogue is one
        // localhost stub — so without a host here nothing at all would be linkable
        // and the positive half of aLinkOutsideTheCatalogueIsRemovedAtTheDoor could
        // not fail. wikipedia.org stands in for "a host the tools really do call".
        config.put("agentic.guardrails.output.extra-allowed-link-hosts", java.util.List.of("wikipedia.org"));
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
    @DisplayName("a link to a host outside the catalogue never leaves the door, in any of its disguises")
    void aLinkOutsideTheCatalogueIsRemovedAtTheDoor() {
        // The single door is where this belongs. A tool that projects can be told to
        // keep a URL-valued field, and a tool that does not project — wikipedia_
        // autocomplete, wikidata_entity_labels, get_random_dog_image — hands its body
        // to the model untouched. Only the transport sees both.
        var response = tools.get("stub", "/mixed-links");

        assertThat(response.isOk()).isTrue();
        assertThat(response.body())
                .as("plain, JSON-escaped and scheme-relative are the same link to a renderer")
                .doesNotContain("doi.org", "bbcgoodfood.com", "example.test");
        // The positive half. Removing every URL would satisfy the assertion above and
        // break get_random_dog_image, whose entire payload is a link to a host that IS
        // in the catalogue.
        assertThat(response.body()).contains("https://pt.wikipedia.org/wiki/Brasil");
        // The model is told, rather than handed a field that silently lost its value.
        assertThat(response.body()).contains("[link removed");
    }

    @Test
    @DisplayName("the guardrail and the tool layer hold one allow-list object, not two derived alike")
    void theGuardrailAndTheToolLayerShareOneAllowList() {
        var toolSide = client.getBean(LinkPolicy.class);
        var guardrailSide = client.getBean(io.github.rodrigorjsf.agenticchat.guardrail.output.LinkAllowList.class);

        // The shipped catalogue merges into every test context — @EachProperty entries
        // in application.yml are additive — so this is the real 60-odd endpoint list
        // with the stub on top, and the unit gate in ToolLinkPolicyTest is no longer
        // the only thing saying the derivation produces anything at all.
        assertThat(toolSide.domains())
                .as("derived from the shipped catalogue, not from a fixture")
                .contains("crossref.org", "stackexchange.com", "dog.ceo");

        // Two objects deriving an equal set from the same property key is a second
        // place to be wrong: rename the key on one side and the guardrail goes on
        // blocking a host the tool layer has started allowing, with nothing red. One
        // object cannot disagree with itself.
        assertThat(guardrailSide.domains())
                .as("the guardrail must consult the same set the door scrubbed against")
                .isSameAs(toolSide.domains());
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
    @DisplayName("a transport ceiling below the body destroys the answer the model could act on")
    void aTransportCeilingBelowTheBodyIsNotABudget() {
        // PINNED, not red: this asserts a property of the HTTP client, not of a change
        // in this repository, so nothing here can be mutated to make it fail. It is
        // here because it is the measurement that decided a number in application.yml,
        // and a number decided by an argument nobody can re-run is a number the next
        // person raises.
        //
        // Issue #1 recommended setting micronaut.http.client.max-content-length to
        // "the largest catalogue ceiling (1 MB, from pokeapi)". That conflates two
        // different numbers. truncate() cannot cut a body it never received, so the
        // transport floor is the largest RAW body any endpoint can return, not the
        // largest max-response-bytes: themealdb's ceiling is 262 144 and its
        // one-letter search measured 2.3 MB. Below the body, this is what happens.
        var tight = new HashMap<String, Object>(Map.of(
                "agentic.tools.apis.stub.base-url", "http://localhost:" + server.getPort() + "/stub",
                "agentic.tools.apis.stub.timeout", "PT2S",
                "agentic.tools.apis.stub.max-retries", 0,
                "agentic.tools.apis.stub.max-response-bytes", 1024,
                "micronaut.http.client.max-content-length", 2048));
        try (var starved = ApplicationContext.run(tight)) {
            var response = starved.getBean(ToolHttpClient.class).get("stub", "/big", Map.of("size", "5000"));

            assertThat(response.outcome())
                    .as("the endpoint budget is 1024 and the body is ~5000, so the shipped "
                            + "configuration answers this with a truncated body and an explicit "
                            + "\"narrow your query\". Put the transport ceiling under the body and "
                            + "the client refuses the response instead, and the model is told the "
                            + "request did not complete — the same request, a worse answer, and on "
                            + "an endpoint with retries it is retried for nothing.")
                    .isEqualTo(ToolResponse.Outcome.UPSTREAM_ERROR);
            assertThat(response.truncated()).isFalse();
            assertThat(response.toModelText()).doesNotContain("truncated");
        }
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

    @Test
    @DisplayName("a 403 is asked once, not retried — a refusal is deterministic")
    void aRefusalIsNotRetried() {
        StubApiController.REFUSED_CALLS.set(0);

        var response = tools.get("stub", "/refused");

        assertThat(response.isOk()).isFalse();
        assertThat(StubApiController.REFUSED_CALLS.get())
                .as("this endpoint has max-retries 1, so a retryable outcome would call twice; "
                        + "retrying a deterministic refusal buys a second refusal and, on a host "
                        + "that 403s an unidentified client, an IP block")
                .isEqualTo(1);
    }
}
