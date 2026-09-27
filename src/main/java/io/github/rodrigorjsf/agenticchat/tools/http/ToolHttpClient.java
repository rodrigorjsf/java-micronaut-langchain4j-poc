package io.github.rodrigorjsf.agenticchat.tools.http;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *   <li><b>No redirect leaves the catalogue.</b> The catalogue stops a tool from
 *       naming a host, but a catalogued host can still answer {@code 302} to
 *       {@code 169.254.169.254}, and an HTTP client that follows redirects by default
 *       re-opens the door the catalogue closed. This class's own client does not follow
 *       ({@code micronaut.http.services.tool-apis.follow-redirects: false}); this class follows
 *       each hop itself, and only to a host some catalogue entry already names —
 *       see {@link #MAX_REDIRECTS}.</li>
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

    /**
     * How many redirect hops one call follows. An upstream that moved a route answers
     * one 301; a chain longer than this is a loop or someone shopping for a host.
     */
    private static final int MAX_REDIRECTS = 3;

    /**
     * The client this class uses, configured under {@code micronaut.http.services.tool-apis}.
     * Its own client, not the default bean: turning redirects off is a decision about
     * the tool door, and on the shared bean it would also change every other consumer
     * — a Langfuse score POST answered with a 3xx would then "succeed" unwritten.
     */
    static final String CLIENT_ID = "tool-apis";

    private final HttpClient httpClient;
    private final Map<String, ApiEndpointProperties> catalogue;
    /** {@code host:port} of every catalogue base URL: the only places a redirect may land. */
    private final Set<String> catalogueOrigins;
    private final String defaultUserAgent;
    private final LinkPolicy links;
    private final Semaphore inFlight = new Semaphore(IN_FLIGHT_LIMIT);

    public ToolHttpClient(@Client(id = CLIENT_ID) HttpClient httpClient,
                          List<ApiEndpointProperties> endpoints,
                          LinkPolicy links,
                          @io.micronaut.context.annotation.Value(
                                  "${agentic.tools.user-agent:agentic-chat-poc/0.1}") String defaultUserAgent) {
        this.httpClient = httpClient;
        this.defaultUserAgent = defaultUserAgent;
        this.links = links;
        this.catalogue = endpoints.stream().collect(Collectors.toUnmodifiableMap(
                ApiEndpointProperties::name, e -> e));
        this.catalogueOrigins = endpoints.stream()
                .map(e -> origin(URI.create(e.baseUrl())))
                .filter(origin -> origin != null)
                .collect(Collectors.toUnmodifiableSet());
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
        // One deadline across every hop, so a redirect chain cannot stretch the
        // endpoint's timeout by the number of hops.
        long deadline = System.nanoTime() + endpoint.timeout().toNanos();
        URI current = uri;
        for (int hop = 0; ; hop++) {
            MutableHttpRequest<?> request = HttpRequest.GET(current)
                    .accept("application/json")
                    .header("User-Agent", userAgent);
            Duration remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
            HttpResponse<String> response = Mono.from(httpClient.exchange(request, String.class))
                    .block(remaining);
            if (response == null || response.code() < 300 || response.code() >= 400) {
                String body = response == null ? null : response.body();
                // Scrubbed AFTER truncation, and the order is deliberate. A cut lands
                // anywhere, so a truncated body can end mid-address — "https://doi.o" is
                // still a host to the output guardrail's URL scan, and still costs the whole
                // answer. Scrubbing the survivor catches the stump too, and spends no cycles
                // on bytes that were already thrown away.
                var truncated = truncate(body == null ? "" : body, endpoint.maxResponseBytes());
                return new ToolResponse(links.scrub(truncated.body()), truncated.outcome(), truncated.truncated());
            }
            // Every refusal below is a value, not an exception: the attempt loop retries
            // exceptions, and a refused hop is deterministic — asking again gets the same
            // Location. The target goes to operators only; an address in the prompt is an
            // address the model can repeat.
            URI next = redirectTarget(current, response);
            String refusal = next == null ? "the service answered with a redirect that has no usable target."
                    : hop == MAX_REDIRECTS ? "the service redirected too many times."
                    : !mayFollow(current, next) ? "the service redirected outside the tool catalogue, which is refused."
                    : null;
            if (refusal != null) {
                LOG.warn("Tool API {} redirect refused after {} hop(s): {} -> {} ({})",
                        endpoint.name(), hop, current, next, refusal);
                return ToolResponse.failure(ToolResponse.Outcome.UPSTREAM_ERROR, refusal);
            }
            current = next;
        }
    }

    private static URI redirectTarget(URI current, HttpResponse<?> response) {
        String location = response.getHeaders().get(HttpHeaders.LOCATION);
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            return current.resolve(location.strip());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * A hop may land only on a host and port a catalogue entry names exactly, and never
     * downgrades https to http. Exact host, not registrable domain: the link policy
     * widens to subdomains because a link is read by a person, while a request is
     * made by this process, and a sibling subdomain of a catalogued API is a host
     * nobody reviewed.
     */
    private boolean mayFollow(URI from, URI to) {
        String scheme = to.getScheme() == null ? "" : to.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && "http".equalsIgnoreCase(from.getScheme()))) {
            return false;
        }
        String origin = origin(to);
        return origin != null && catalogueOrigins.contains(origin);
    }

    /** {@code host:port}, with the scheme's default port filled in; null without a host. */
    private static String origin(URI uri) {
        if (uri.getHost() == null) {
            return null;
        }
        int port = uri.getPort() != -1 ? uri.getPort()
                : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
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
