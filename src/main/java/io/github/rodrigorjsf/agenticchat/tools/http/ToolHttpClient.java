package io.github.rodrigorjsf.agenticchat.tools.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The single door every tool goes through to reach the internet.
 *
 * <p>One low-level client rather than sixty {@code @Client} interfaces: the tool
 * catalogue is data, not types, and generating sixty declarative clients would
 * multiply annotation-processing time for no behavioural gain.
 *
 * <p>What this class is responsible for, and why each item is here rather than in
 * each tool:
 *
 * <ul>
 *   <li><b>No tool can name a host.</b> Callers pass a catalogue key and a path;
 *       the base URL comes from {@link ApiEndpointProperties}. That removes SSRF as
 *       a category rather than filtering for it.</li>
 *   <li><b>Bounded output.</b> Every response is capped at the endpoint's byte
 *       budget, and truncation is reported to the model instead of silently
 *       dropping data.</li>
 *   <li><b>Allow-listed links.</b> A body may not carry an address to a host outside
 *       the catalogue — see {@link LinkPolicy}. It is here rather than in each tool
 *       because a tool that projects can be told to keep a URL-valued field and a
 *       tool that does not project hands its body over untouched; the door is the
 *       only point that sees both. The cost of missing one is not tokens: the
 *       output guardrail withholds the entire answer, so an ordinary question is
 *       answered "The response was withheld by the output policy."
 *       <p>Read as a substitution, not as a second ceiling. It runs <em>after</em> the
 *       truncation above, and {@code LinkPolicy.REMOVED} is 42 characters, so
 *       replacing anything shorter than that grows the body: one already at its byte
 *       budget can finish over it. That ordering is deliberate and not worth trading
 *       away — both the projection and the guardrail's own URL scan need a whole
 *       address, and a cut that lands mid-host leaves a stump the scan still reads as
 *       a host. Only the byte budget above is enforced.</p></li>
 *   <li><b>Failures are values.</b> Nothing throws at the tool boundary, so
 *       LangChain4j never falls back to its default handler, which puts
 *       {@code Throwable.getMessage()} — upstream URLs, bodies, stack traces —
 *       straight into the prompt.</li>
 *   <li><b>Bounded retries.</b> Only idempotent GETs, only on timeouts and 5xx,
 *       never on 4xx or 429. A model already retries by calling the tool again;
 *       retrying underneath it multiplies the load on a free public API.</li>
 *   <li><b>Bounded concurrency.</b> A permit per in-flight request, because the
 *       calling threads are cheap and the memory behind them is not — see
 *       {@link #IN_FLIGHT_LIMIT}.</li>
 * </ul>
 */
@Singleton
public class ToolHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(ToolHttpClient.class);
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(250);

    /**
     * How many tool requests may be in flight at once, across every endpoint.
     *
     * <p>Virtual threads make the caller free and the buffer behind it expensive. A
     * response is read whole into a String before anything trims it, so the transient
     * cost of one call is a multiple of the wire size — and the largest ceiling in the
     * catalogue admits a body measured at 664 KB. Unbounded fan-out turns that into
     * heap pressure that looks like a leak, on a path where nothing else says stop.
     *
     * <p>Twelve is not tuned; it is the number of concurrent lookups a handful of
     * simultaneous conversations can produce, and it is sized to the free public APIs
     * downstream rather than to this process. Raise it when a measurement, not a
     * feeling, says requests are queueing.
     */
    private static final int IN_FLIGHT_LIMIT = 12;

    /**
     * How long a call waits for a permit before giving up. Long enough to ride out a
     * burst, short enough that the model gets an answer it can act on rather than a
     * turn that stalls.
     */
    private static final Duration PERMIT_WAIT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final Map<String, ApiEndpointProperties> catalogue;
    private final String defaultUserAgent;
    private final LinkPolicy links;
    private final Semaphore inFlight = new Semaphore(IN_FLIGHT_LIMIT);

    public ToolHttpClient(HttpClient httpClient,
                          List<ApiEndpointProperties> endpoints,
                          LinkPolicy links,
                          @io.micronaut.context.annotation.Value(
                                  "${agentic.tools.user-agent:agentic-chat-poc/0.1}") String defaultUserAgent) {
        this.httpClient = httpClient;
        this.defaultUserAgent = defaultUserAgent;
        this.links = links;
        this.catalogue = endpoints.stream().collect(Collectors.toUnmodifiableMap(
                ApiEndpointProperties::name, e -> e));
        LOG.info("Tool API catalogue: {}", new java.util.TreeSet<>(catalogue.keySet()));
    }

    /**
     * Endpoint keys a tool may use. Exposed so a startup check can verify them.
     */
    public java.util.Set<String> knownApis() {
        return catalogue.keySet();
    }

    public ToolResponse get(String apiName, String path) {
        return get(apiName, path, Map.of());
    }

    /**
     * @param apiName catalogue key, e.g. {@code brasilapi}
     * @param path    path under that API's base URL, e.g. {@code /cep/v2/01310100}
     * @param query   query parameters; values are encoded, never concatenated
     */
    public ToolResponse get(String apiName, String path, Map<String, String> query) {
        var endpoint = catalogue.get(apiName);
        if (endpoint == null) {
            // A programming error, not a model error: the tool named an API that is
            // not in the catalogue. Fail loudly in our own logs, stay vague to the model.
            LOG.error("Tool asked for unknown API '{}'. Known: {}", apiName, catalogue.keySet());
            return ToolResponse.failure(ToolResponse.Outcome.UPSTREAM_ERROR,
                    "this data source is not configured.");
        }

        URI uri = buildUri(endpoint, path, query);
        int attempts = endpoint.maxRetries() + 1;

        boolean admitted;
        try {
            admitted = inFlight.tryAcquire(PERMIT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolResponse.failure(ToolResponse.Outcome.UPSTREAM_ERROR,
                    "the request to " + endpoint.name() + " was cancelled.");
        }
        if (!admitted) {
            LOG.warn("Tool request to {} gave up waiting for an in-flight permit ({} in use)",
                    endpoint.name(), IN_FLIGHT_LIMIT);
            return ToolResponse.failure(ToolResponse.Outcome.RATE_LIMITED,
                    "too many lookups are in progress. Answer from what you already have.");
        }
        try {
            return attempt(endpoint, uri, attempts);
        } finally {
            inFlight.release();
        }
    }

    private ToolResponse attempt(ApiEndpointProperties endpoint, URI uri, int attempts) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return read(endpoint, uri);
            } catch (HttpClientResponseException e) {
                var mapped = mapStatus(e.getStatus());
                if (mapped.outcome() != ToolResponse.Outcome.UPSTREAM_ERROR || attempt == attempts) {
                    logUpstream(endpoint, uri, e.getStatus().getCode(), null);
                    return mapped;
                }
            } catch (RuntimeException e) {
                if (attempt == attempts) {
                    logUpstream(endpoint, uri, -1, e);
                    return ToolResponse.failure(ToolResponse.Outcome.UPSTREAM_ERROR,
                            "the request to " + endpoint.name() + " did not complete.");
                }
            }
            sleepBeforeRetry(attempt);
        }
        return ToolResponse.failure(ToolResponse.Outcome.UPSTREAM_ERROR,
                "the request to " + endpoint.name() + " did not complete.");
    }

    /**
     * Reactor rather than {@code toBlocking()} for one reason: the blocking client
     * has no per-request timeout, only the client-wide one. Tool endpoints have very
     * different latency profiles, and a slow public API must not be able to hold a
     * conversation open for the global timeout.
     */
    private ToolResponse read(ApiEndpointProperties endpoint, URI uri) {
        // One User-Agent for every host by default. Java's own default is
        // "Java-http-client/<version>", which is the exact shape Wikimedia's bot
        // policy rejects — so leaving it unset is a live 403, not a curiosity.
        String userAgent = endpoint.userAgent() == null || endpoint.userAgent().isBlank()
                ? defaultUserAgent
                : endpoint.userAgent();
        MutableHttpRequest<?> request = HttpRequest.GET(uri)
                .accept("application/json")
                .header("User-Agent", userAgent);
        String body = Mono.from(httpClient.retrieve(request, String.class))
                .block(endpoint.timeout());
        // Scrubbed AFTER truncation, and the order is deliberate. A cut lands
        // anywhere, so a truncated body can end mid-address — "https://doi.o" is
        // still a host to the output guardrail's URL scan, and still costs the whole
        // answer. Scrubbing the survivor catches the stump too, and spends no cycles
        // on bytes that were already thrown away.
        var truncated = truncate(body == null ? "" : body, endpoint.maxResponseBytes());
        return new ToolResponse(links.scrub(truncated.body()), truncated.outcome(), truncated.truncated());
    }

    private static ToolResponse truncate(String body, int maxBytes) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return ToolResponse.ok(body, false);
        }
        // Cut on a code-point boundary so the model never sees a mangled character.
        var decoded = new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
        if (!decoded.isEmpty() && Character.isHighSurrogate(decoded.charAt(decoded.length() - 1))) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return ToolResponse.ok(decoded, true);
    }

    private static ToolResponse mapStatus(HttpStatus status) {
        return switch (status.getCode()) {
            case 400, 422 -> ToolResponse.failure(ToolResponse.Outcome.INVALID_REQUEST,
                    "the service rejected these arguments.");
            // NOT_FOUND rather than UPSTREAM_ERROR, which is the one outcome the retry
            // loop treats as retryable. A refusal is deterministic — the same request
            // gets the same 403 — so retrying spends a second request to be refused
            // twice, and against a host that answers 403 to an unidentified client it
            // is exactly the traffic that gets an IP blocked.
            case 401, 403 -> ToolResponse.failure(ToolResponse.Outcome.NOT_FOUND,
                    "this data source refused the request.");
            case 404 -> ToolResponse.failure(ToolResponse.Outcome.NOT_FOUND,
                    "the service has no record matching those arguments.");
            case 429 -> ToolResponse.failure(ToolResponse.Outcome.RATE_LIMITED,
                    "this data source is throttling requests.");
            default -> ToolResponse.failure(ToolResponse.Outcome.UPSTREAM_ERROR,
                    "the service returned an error.");
        };
    }

    private static URI buildUri(ApiEndpointProperties endpoint, String path, Map<String, String> query) {
        var builder = UriBuilder.of(URI.create(endpoint.baseUrl() + normalise(path)));
        query.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                builder.queryParam(k, v);
            }
        });
        return builder.build();
    }

    private static String normalise(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Upstream URLs and status codes are useful to operators and dangerous in a
     * prompt, so they are logged here and never returned.
     */
    private void logUpstream(ApiEndpointProperties endpoint, URI uri, int status, Exception cause) {
        if (cause != null) {
            LOG.warn("Tool API {} failed: {}", endpoint.name(), uri, cause);
        } else {
            LOG.warn("Tool API {} returned {}: {}", endpoint.name(), status, uri);
        }
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF.toMillis() * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
