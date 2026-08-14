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
    @Get("/flaky")
    public HttpResponse<String> flaky() {
        return FLAKY_CALLS.incrementAndGet() == 1
                ? HttpResponse.serverError("transient")
                : HttpResponse.ok("{\"recovered\":true}");
    }

    @Get("/slow")
    public String slow() throws InterruptedException {
        Thread.sleep(3_000);
        return "{\"late\":true}";
    }
}
