package io.github.rodrigorjsf.agenticchat.tools.http;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.core.annotation.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the public APIs the tools call.
 *
 * <p>Micronaut's own embedded server rather than a stubbing library: the project
 * already depends on it, it speaks real HTTP over a real socket, and it can model
 * the failure shapes that matter (slow, throttled, oversized) without another
 * dependency to keep current.
 */
@Controller("/stub")
@Requires(property = "stub.api.enabled", value = "true")
public class StubApiController {

    /** Counts calls so the retry policy can be asserted rather than assumed. */
    public static final AtomicInteger FLAKY_CALLS = new AtomicInteger();

    @Get("/ok")
    public String ok() {
        return "{\"city\":\"Sao Paulo\"}";
    }

    @Get("/echo-query")
    public String echoQuery(@QueryValue String q) {
        return "{\"q\":\"" + q + "\"}";
    }

    @Get("/echo-agent")
    public String echoAgent(@Header(name = "User-Agent", defaultValue = "none") String userAgent) {
        return "{\"ua\":\"" + userAgent + "\"}";
    }

    @Get("/big")
    public String big(@QueryValue @Nullable Integer size) {
        return "x".repeat(size == null ? 100_000 : size);
    }

    @Get("/missing")
    public HttpResponse<String> missing() {
        return HttpResponse.status(HttpStatus.NOT_FOUND).body("nope");
    }

    @Get("/bad-args")
    public HttpResponse<String> badArgs() {
        return HttpResponse.status(HttpStatus.BAD_REQUEST).body("bad");
    }

    @Get("/throttled")
    public HttpResponse<String> throttled() {
        return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS).body("slow down");
    }

    @Get("/broken")
    public HttpResponse<String> broken() {
        return HttpResponse.serverError("boom: /var/secrets/api-key.txt not readable");
    }

    /** Fails on the first call and succeeds afterwards. */
    /** Counts its calls, so a test can prove a deterministic refusal is asked once. */
    public static final AtomicInteger REFUSED_CALLS = new AtomicInteger();

    @Get("/refused")
    public HttpResponse<String> refused() {
        REFUSED_CALLS.incrementAndGet();
        return HttpResponse.status(HttpStatus.FORBIDDEN).body("forbidden");
    }

    @Get("/flaky")
    public HttpResponse<String> flaky() {
        return FLAKY_CALLS.incrementAndGet() == 1
                ? HttpResponse.serverError("transient")
                : HttpResponse.ok("{\"recovered\":true}");
    }

    // ------------------------------------------------------------------
    // Recorded-shape bodies for the link-policy tests.
    //
    // These are not inventions: each carries the URL-valued fields the real
    // upstream documents for that route, and nothing else about them matters.
    // They exist so a projection can be run for real — the same tool method, the
    // same ToolJson call — against a body shaped like the one it will meet.
    // ------------------------------------------------------------------

    /** Crossref's single-work route. The record's own {@code URL} is a doi.org address. */
    @Get("/crossref/works/{+doi}")
    public String crossrefWork(String doi) {
        return """
                {"status":"ok","message-type":"work","message-version":"1.0.0","message":{
                "DOI":"%s",
                "URL":"https://doi.org/%s",
                "title":["Applying Deep Learning to Airbnb Search"],
                "container-title":["KDD '19"],
                "publisher":"ACM",
                "is-referenced-by-count":42,
                "abstract":"<jats:p>A short abstract.</jats:p>",
                "issued":{"date-parts":[[2019,7,25]]},
                "author":[{"given":"Malay","family":"Haldar","sequence":"first",
                "ORCID":"http://orcid.org/0000-0002-1825-0097","authenticated-orcid":true}]}}
                """.formatted(doi, doi);
    }

    /**
     * Crossref's search route. {@code select} constrains the fields, and an author
     * object still carries an {@code ORCID} — a URL on a host no tool ever calls.
     */
    @Get("/crossref/works")
    public String crossrefSearch() {
        return """
                {"status":"ok","message-type":"work-list","message":{"total-results":812,
                "items":[{"DOI":"10.1145/3292500.3330701",
                "title":["Applying Deep Learning to Airbnb Search"],
                "container-title":["KDD '19"],
                "issued":{"date-parts":[[2019,7,25]]},
                "author":[{"given":"Malay","family":"Haldar","sequence":"first",
                "ORCID":"http://orcid.org/0000-0002-1825-0097"}]}]}}
                """;
    }

    /**
     * OpenAlex's search route. {@code id} is an openalex.org address — in the
     * catalogue, so an answer may cite it — and {@code doi} is a doi.org one, which
     * is not.
     */
    @Get("/openalex/works")
    public String openalexSearch() {
        return """
                {"meta":{"count":1,"per_page":3},
                "results":[{"id":"https://openalex.org/W2741809807",
                "doi":"https://doi.org/10.7717/peerj.4375",
                "title":"The state of OA","publication_year":2018,"cited_by_count":935}]}
                """;
    }

    /**
     * One body carrying a catalogue host, two hosts outside it, and the two
     * disguises a plain {@code https://} scan misses: JSON-escaped slashes, which a
     * PHP-backed API emits, and a scheme-relative address, which MediaWiki does.
     */
    @Get("/mixed-links")
    public String mixedLinks() {
        return """
                {"allowed":"https://pt.wikipedia.org/wiki/Brasil",
                "resolver":"https://doi.org/10.7717/peerj.4375",
                "escaped":"https:\\/\\/www.bbcgoodfood.com\\/recipes\\/lasagne",
                "schemeRelative":"//www.example.test/w/Q155"}
                """;
    }

    /**
     * Open-Meteo's forecast route, trimmed to the fields a rain question reads. The
     * scenario-suite test grounds its scripted answer on {@code 12.4}.
     */
    @Get("/open-meteo/v1/forecast")
    public String openMeteoForecast() {
        return """
                {"latitude":-23.55,"longitude":-46.63,"timezone":"America/Sao_Paulo",
                "daily":{"time":["2026-09-26","2026-09-27"],
                "precipitation_sum":[0.0,12.4],"temperature_2m_max":[24.1,19.8]}}
                """;
    }

    // ------------------------------------------------------------------
    // Redirect routes for the redirect-hop tests.
    //
    // An open redirect on purpose: a catalogued host that answers 302 to wherever
    // the test says is exactly the shape an attacker needs — a compromised or
    // misconfigured upstream bouncing the tool client to 169.254.169.254.
    // ------------------------------------------------------------------

    /** Counts arrivals, so a test can prove a refused hop was never requested. */
    public static final AtomicInteger LANDED_CALLS = new AtomicInteger();

    @Get("/redirect")
    public HttpResponse<String> redirect(@QueryValue String to) {
        return HttpResponse.<String>status(HttpStatus.FOUND).header("Location", to);
    }

    @Get("/landed")
    public String landed() {
        LANDED_CALLS.incrementAndGet();
        return "{\"landed\":true}";
    }

    @Get("/slow")
    public String slow() throws InterruptedException {
        Thread.sleep(3_000);
        return "{\"late\":true}";
    }

    // ------------------------------------------------------------------
    // Failure routes for upstream-failure scenarios.
    //
    // A scenario points a whole catalogue key's base-url here, and the tool then
    // appends its own path ("/v1/forecast?..."), so each route swallows whatever
    // path and query follow it and fails the same way for every one.
    // ------------------------------------------------------------------

    @Get("/fail/server-error/{+path}")
    public HttpResponse<String> failWithServerError(String path) {
        return HttpResponse.serverError("upstream failure injected by the scenario suite");
    }

    /** Longer than the one-second timeout the timeout shape gives its catalogue key. */
    @Get("/fail/timeout/{+path}")
    public String failWithTimeout(String path) throws InterruptedException {
        return slow();
    }

    /**
     * One MiB of valid JSON: above the largest {@code max-response-bytes} in the catalogue
     * (512 KiB), so any key pointed here is truncated at its own ceiling.
     */
    @Get("/fail/oversized/{+path}")
    public String failWithOversizedBody(String path) {
        return "{\"padding\":\"" + "x".repeat(1 << 20) + "\"}";
    }
}
