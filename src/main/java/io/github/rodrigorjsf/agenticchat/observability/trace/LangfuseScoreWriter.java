package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes scores to a Langfuse instance, off the request path.
 *
 * <h2>Why there is a queue and a thread here at all</h2>
 * <p>Scores are the one part of the Langfuse data model that does not arrive over OTLP, so
 * none of the batching the span exporter already does applies to them: without this class
 * a score would be a synchronous {@code POST} inside the turn that produced it.
 *
 * <p>That is not affordable. The triage judge in this application is justified entirely by
 * a measured 0.91 s median — a cheap model in front of an expensive one, chosen so the
 * common case is fast. Putting an HTTP round trip to a third-party backend inside the same
 * turn to record what the judge decided spends the saving on the bookkeeping about the
 * saving.
 *
 * <p>So {@link #record} does one bounded, non-blocking {@code offer} and returns, and a
 * single background consumer does the network.
 *
 * <h2>Full means DROP, and what that costs</h2>
 * <p>A full queue means the backend is slower than the application is producing scores.
 * Blocking the caller there is the worst available answer: it converts a Langfuse outage
 * into user-visible latency on every turn, exactly when the queue is fullest.
 *
 * <p><b>A dropped score costs one missing evaluation on one trace.</b> The trace itself,
 * its observations, its inputs and outputs, its token usage and its cost all still arrive
 * — they travel over OTLP on a different queue with a different exporter, and nothing here
 * touches them. What is lost is a chart point. The alternative is a slower answer for a
 * user who did not ask for the chart, and that is not a close call.
 *
 * <p>Drops are counted rather than merely logged, because the number is the signal: one
 * drop is noise, a rising count is "the score queue is undersized or Langfuse is down",
 * and only a counter can tell those apart.
 */
@Singleton
// The same three-property gate as LangfuseOtlpSettings, and a pattern rather than a bare
// presence check for the same reason: a property set to an empty string is present, and a
// client built on an empty host fails somewhere less obvious than here.
@Requires(property = LangfuseProperties.PREFIX + ".host", pattern = "\\S+")
@Requires(property = LangfuseProperties.PREFIX + ".public-key", pattern = "\\S+")
@Requires(property = LangfuseProperties.PREFIX + ".secret-key", pattern = "\\S+")
public class LangfuseScoreWriter implements ScoreWriter {

    private static final Logger LOG = LoggerFactory.getLogger(LangfuseScoreWriter.class);

    private static final String SCORES_PATH = "/api/public/scores";

    /**
     * How long shutdown waits for an in-flight write before giving up on it.
     *
     * <p>Deliberately short, and deliberately not a drain. A shutdown that waits on N
     * queued HTTP round trips to a backend that may be the reason the queue is long turns
     * a rolling restart into an outage — and a score lost at shutdown costs exactly what a
     * score dropped by a full queue costs, which the class comment above already accepts.
     */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(2);

    /**
     * A full queue empties in bursts, so one line per drop turns an observability problem
     * into a log flood on the very run that is already struggling. The first drop is the
     * one worth seeing; after that the count carries the story.
     */
    private static final long DROP_LOG_INTERVAL = 100;

    private final BlockingQueue<PendingScore> queue;
    private final int capacity;
    private final AtomicLong dropped = new AtomicLong();
    private final AgentTracer tracer;
    private final HttpClient http;
    private final JsonMapper json;
    private final String endpoint;
    private final String authorization;
    private final String environment;
    private final Duration timeout;

    private volatile boolean running = true;
    private volatile Thread consumer;

    public LangfuseScoreWriter(
            LangfuseProperties properties,
            DeploymentIdentity deployment,
            AgentTracer tracer,
            HttpClient http,
            JsonMapper json,
            @Value("${" + LangfuseProperties.PREFIX + ".scores.queue-capacity:1024}") int queueCapacity,
            @Value("${" + LangfuseProperties.PREFIX + ".scores.timeout:10s}") Duration timeout) {
        this.tracer = tracer;
        this.http = http;
        this.json = json;
        this.timeout = timeout;
        this.capacity = Math.max(1, queueCapacity);
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.endpoint = trimTrailingSlash(properties.getHost()) + SCORES_PATH;
        // HTTP Basic over publicKey:secretKey, not a bearer token — the same credential
        // LangfuseOtlpSettings encodes for the OTLP leg. It is derived twice because the
        // two transports do not share a header set: the OTLP leg also carries
        // x-langfuse-ingestion-version, which means nothing on the Scores API. Both
        // derivations read the same LangfuseProperties bean, so there is one source of
        // truth for the credential even though there are two encodings of it.
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (properties.getPublicKey() + ":" + properties.getSecretKey())
                        .getBytes(StandardCharsets.UTF_8));
        // The same environment the spans carry. A score filed under a different one than
        // its trace is invisible behind Langfuse's environment filter: the trace shows up
        // and its evaluation does not, which reads as "the score was never written".
        this.environment = deployment.environment();
    }

    /**
     * Started here rather than in the constructor so the consumer cannot observe a
     * half-built instance — it reads every field above on its first iteration.
     */
    @PostConstruct
    void start() {
        // A platform thread, not a virtual one: there is exactly one, it spends its life
        // blocked on a queue or a socket, and the scheduler saving is nil. What a named
        // daemon thread buys instead is a line in a thread dump when scores stop arriving,
        // and a JVM that is not held open by its own bookkeeping.
        this.consumer = Thread.ofPlatform()
                .name("langfuse-score-writer")
                .daemon(true)
                .start(this::drain);
        LOG.info("Writing Langfuse scores to {} (queue capacity {})", endpoint, capacity);
    }

    @PreDestroy
    void stop() {
        running = false;
        Thread thread = consumer;
        if (thread == null) {
            return;
        }
        // Interrupt rather than wait for the queue to empty: take() and the blocking read
        // in post() both unblock on it, and SHUTDOWN_WAIT exists to bound the case where
        // neither does.
        thread.interrupt();
        try {
            thread.join(SHUTDOWN_WAIT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void record(Score score) {
        try {
            var target = tracer.current();
            if (target.isEmpty()) {
                // Debug, not warn. Nothing being observed is the normal state of a
                // deployment with tracing switched off, and a warning here would fire on
                // every turn of every such run.
                LOG.debug("Dropping score '{}': no observation is open", nameOf(score));
                return;
            }
            record(target.get(), score);
        } catch (RuntimeException e) {
            // Unreachable in principle — tracer.current() reads a thread local. Present
            // because this method is called from inside the turn it is measuring, and the
            // rule for everything in this package is that observing cannot break the thing
            // being observed.
            LOG.warn("Could not record score '{}'", nameOf(score), e);
        }
    }

    @Override
    public void record(ObservationRef target, Score score) {
        try {
            if (score == null || target == null || isBlank(target.traceId())) {
                // A score names exactly one subject, and an observationId is only
                // meaningful under a traceId — without one there is nothing to attach to.
                LOG.debug("Dropping score '{}': no trace to attach it to", nameOf(score));
                return;
            }
            if (!queue.offer(new PendingScore(target, score))) {
                long total = dropped.incrementAndGet();
                if (total == 1 || total % DROP_LOG_INTERVAL == 0) {
                    LOG.warn("Langfuse score queue is full; {} score(s) dropped so far, "
                            + "most recently '{}'. Traces are unaffected.", total, score.name());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not record score '{}'", nameOf(score), e);
        }
    }

    /**
     * How many scores have been dropped because the queue was full. A gauge on this is
     * what separates "Langfuse is quiet" from "we stopped telling Langfuse anything".
     */
    public long droppedScores() {
        return dropped.get();
    }

    private void drain() {
        while (running) {
            PendingScore pending;
            try {
                pending = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            post(pending);
        }
    }

    /**
     * One write. Swallows everything: a consumer that dies on a 400 stops writing every
     * later score too, and nothing in this application would notice.
     */
    private void post(PendingScore pending) {
        try {
            var request = HttpRequest.POST(endpoint, json.writeValueAsString(bodyOf(pending)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", authorization);
            // Reactor rather than toBlocking(), for the reason ToolHttpClient gives: the
            // blocking client honours only the client-wide timeout, and this call must be
            // bounded by its own so a hung backend cannot park the consumer forever and
            // silently turn every later score into a drop.
            Mono.from(http.retrieve(request, String.class)).block(timeout);
        } catch (Exception e) {
            if (!running || Thread.currentThread().isInterrupted()) {
                // Reactor turns an interrupt during block() into an unchecked exception.
                // At shutdown that is the interrupt above doing its job, not a failure.
                LOG.debug("Abandoned score '{}' during shutdown", pending.score().name());
                return;
            }
            // The message may carry the endpoint and the response body, which is exactly
            // why it is logged here and never handed back to a caller.
            LOG.warn("Could not write score '{}' to Langfuse", pending.score().name(), e);
        }
    }

    /**
     * The {@code CreateScoreRequest} body, built as a map and serialised rather than
     * declared as a type: an absent field must be absent, not {@code null}, and a
     * hand-built map makes that visible at the point where each field is decided.
     *
     * <p>No client-generated {@code id}. An id would make the write idempotent, which
     * would matter if this class retried — it does not, precisely so that a slow backend
     * cannot multiply its own load.
     */
    private Map<String, Object> bodyOf(PendingScore pending) {
        Score score = pending.score();
        var body = new LinkedHashMap<String, Object>();
        body.put("traceId", pending.target().traceId());
        if (!isBlank(pending.target().observationId())) {
            body.put("observationId", pending.target().observationId());
        }
        body.put("name", score.name());
        body.put("value", score.wireValue());
        body.put("dataType", score.dataType().name());
        if (!isBlank(score.comment())) {
            body.put("comment", score.comment());
        }
        if (!isBlank(environment)) {
            body.put("environment", environment);
        }
        return body;
    }

    private static String nameOf(Score score) {
        return score == null ? "<null>" : score.name();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimTrailingSlash(String host) {
        return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }

    /**
     * A score and the subject it names, captured at {@code record} time. The subject is
     * resolved on the caller's thread on purpose: by the time the consumer runs, the
     * observation that produced the score is closed and its context is gone.
     */
    private record PendingScore(ObservationRef target, Score score) {
    }
}
