package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in for {@code POST /api/public/scores} on a Langfuse instance.
 *
 * <p>Micronaut's own embedded server rather than a stubbing library, for the reason
 * {@code StubApiController} gives: the project already depends on it, it speaks real HTTP
 * over a real socket, and it can model the shapes that matter here — a slow response and a
 * rejected write — without another dependency to keep current. It also means the assertions
 * are made against a body a server actually parsed, so a malformed one fails the test rather
 * than passing a string comparison.
 *
 * <p>State is on the instance, not in static fields: the writer under test has a background
 * thread, so a request can land while a later test is running, and per-instance state means
 * a context per test class rather than a whole JVM's worth of leakage.
 */
@Controller("/api/public")
@Requires(property = "stub.langfuse.enabled", value = "true")
public class StubLangfuseScoresController {

    /** A score name the stub refuses, so the consumer's survival of a 5xx is testable. */
    static final String REJECTED_SCORE_NAME = "boom";

    private static final Duration MAX_HOLD = Duration.ofSeconds(10);

    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicReference<CountDownLatch> hold = new AtomicReference<>(new CountDownLatch(0));

    record Received(Map<String, Object> body, String authorization) {
    }

    /**
     * Binds the body as a {@code Map} rather than as raw text on purpose: the parse is part
     * of the assertion. It also preserves the JSON number form — an integral {@code 1}
     * arrives as an {@code Integer} and {@code 1.0} as a {@code Double} — which is how the
     * boolean score's wire form is checked without reading bytes.
     */
    @Post("/scores")
    public HttpResponse<Map<String, String>> createScore(
            @Body Map<String, Object> body,
            @Header(name = "Authorization", defaultValue = "") String authorization)
            throws InterruptedException {
        received.add(new Received(body, authorization));
        int id = count.incrementAndGet();
        // Bounded even when a test forgets to release: a stub that can park a thread
        // forever turns one broken assertion into a suite that never finishes.
        hold.get().await(MAX_HOLD.toMillis(), TimeUnit.MILLISECONDS);
        if (REJECTED_SCORE_NAME.equals(body.get("name"))) {
            return HttpResponse.serverError(Map.of("error", "rejected"));
        }
        return HttpResponse.created(Map.of("id", "score-" + id));
    }

    /**
     * Makes every subsequent request park until the returned latch is counted down, so a
     * test can prove what the producer does while the consumer is stuck in a write.
     */
    CountDownLatch holdRequests() {
        var latch = new CountDownLatch(1);
        hold.set(latch);
        return latch;
    }

    void releaseRequests() {
        CountDownLatch previous = hold.getAndSet(new CountDownLatch(0));
        previous.countDown();
    }

    /**
     * @return the next request, failing the test rather than returning null if none arrives
     */
    Received awaitNext() {
        Received next = poll(Duration.ofSeconds(5));
        if (next == null) {
            throw new AssertionError("no score reached the Langfuse stub within 5s");
        }
        return next;
    }

    Received poll(Duration timeout) {
        try {
            return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a score", e);
        }
    }

    int receivedCount() {
        return count.get();
    }

    void reset() {
        releaseRequests();
        received.clear();
        count.set(0);
    }
}
