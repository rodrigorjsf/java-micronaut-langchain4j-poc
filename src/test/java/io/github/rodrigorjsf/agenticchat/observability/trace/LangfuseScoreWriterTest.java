package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/**
 * The Scores API leg, asserted through requests a real server received.
 *
 * <p>The seam is {@link ScoreWriter} and the observable is the HTTP request Langfuse would
 * get — never the queue's internals. A score that never leaves the process is worth exactly
 * as much as one that was never recorded, and only the far end of the socket can tell the
 * two apart.
 *
 * <p>The queue is deliberately configured with a capacity of one. Every test here records a
 * single score and waits for it, so the small capacity costs nothing — and it is what makes
 * the drop-when-full case reachable without a load loop.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LangfuseScoreWriterTest {

    private static final String PUBLIC_KEY = "pk-lf-0123456789";
    private static final String SECRET_KEY = "sk-lf-9876543210";

    /**
     * A well-formed OTLP id pair, for the tests that name their subject explicitly rather
     * than letting the tracer supply it.
     */
    private static final ObservationRef TARGET =
            new ObservationRef("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331");

    private EmbeddedServer langfuse;
    private StubLangfuseScoresController scores;
    private ApplicationContext ctx;
    private LangfuseScoreWriter writer;
    private AgentTracer tracer;

    @BeforeAll
    void startStubAndWriter() {
        langfuse = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "stub.langfuse.enabled", "true",
                "micronaut.server.port", -1));
        scores = langfuse.getApplicationContext().getBean(StubLangfuseScoresController.class);

        ctx = ApplicationContext.run(configuration("http://localhost:" + langfuse.getPort()));
        writer = ctx.getBean(LangfuseScoreWriter.class);
        tracer = ctx.getBean(AgentTracer.class);
    }

    @AfterAll
    void stop() {
        if (ctx != null) {
            ctx.close();
        }
        if (langfuse != null) {
            langfuse.close();
        }
    }

    @BeforeEach
    void forgetEarlierRequests() {
        scores.reset();
    }

    @Test
    @DisplayName("a score names the observation that was open when it was recorded")
    void aScoreNamesTheObservationItWasRecordedIn() {
        ObservationRef open;
        try (var observation = tracer.start("turn", ObservationType.AGENT)) {
            open = tracer.current().orElseThrow();
            writer.record(Score.numeric("triage_confidence", 0.83));
        }

        Map<String, Object> body = scores.awaitNext().body();
        assertThat(body)
                .containsEntry("traceId", open.traceId())
                .containsEntry("observationId", open.observationId())
                .containsEntry("name", "triage_confidence")
                .containsEntry("dataType", "NUMERIC");
        assertThat(((Number) body.get("value")).doubleValue()).isEqualTo(0.83);
    }

    @Test
    @DisplayName("the write is HTTP Basic over publicKey:secretKey, not a bearer token")
    void theWriteCarriesBasicCredentials() {
        writer.record(TARGET, Score.numeric("triage_confidence", 0.5));

        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (PUBLIC_KEY + ":" + SECRET_KEY).getBytes(StandardCharsets.UTF_8));
        assertThat(scores.awaitNext().authorization()).isEqualTo(expected);
    }

    @Test
    @DisplayName("an explicitly targeted score carries every field the Scores API documents")
    void anExplicitlyTargetedScoreIsComplete() {
        writer.record(TARGET, Score.categorical("triage_decision", "IN_SCOPE")
                .withComment("asked about the weather"));

        assertThat(scores.awaitNext().body())
                .containsEntry("traceId", TARGET.traceId())
                .containsEntry("observationId", TARGET.observationId())
                .containsEntry("name", "triage_decision")
                .containsEntry("value", "IN_SCOPE")
                .containsEntry("dataType", "CATEGORICAL")
                .containsEntry("comment", "asked about the weather")
                // The spans carry this too. A score filed under a different environment
                // from its trace is invisible behind Langfuse's environment filter: the
                // trace is listed, its evaluation is not, and it reads as a lost write.
                .containsEntry("environment", "test-env");
    }

    @Test
    @DisplayName("a boolean score is written as the integer 1, never as 1.0")
    void aBooleanScoreIsIntegralOnTheWire() {
        writer.record(TARGET, Score.bool("answered_in_scope", true));

        Object value = scores.awaitNext().body().get("value");
        // The stub parsed this out of the JSON, so the assertion is about the token that
        // was on the wire: a Double here would mean the body said 1.0, which is a number
        // to a lenient parser and not what the schema documents for a BOOLEAN score.
        assertThat(value).isNotInstanceOf(Double.class).isNotInstanceOf(Float.class);
        assertThat(((Number) value).longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a score recorded with no observation open is dropped, not thrown")
    void noObservationOpenDropsTheScore() {
        assertThatCode(() -> writer.record(Score.bool("answered_in_scope", true)))
                .doesNotThrowAnyException();

        // Nothing to attach it to means nothing to send. Asserted by absence, because the
        // failure being guarded against is an exception in the turn — and a turn that threw
        // would have failed above, while a score invented out of nowhere would fail here.
        assertThat(scores.poll(Duration.ofMillis(500))).isNull();
    }

    @Test
    @DisplayName("a full queue drops scores instead of blocking the turn recording them")
    void aFullQueueDropsRatherThanBlocks() {
        long droppedBefore = writer.droppedScores();
        var held = scores.holdRequests();
        try {
            writer.record(TARGET, Score.numeric("first", 1));
            // Not a sleep: once the stub has the first request, the consumer is parked
            // inside that write and the queue is provably empty. Everything after this
            // point is exact arithmetic rather than a guess about scheduling.
            await().atMost(Duration.ofSeconds(5)).until(() -> scores.receivedCount() == 1);

            long startedAt = System.nanoTime();
            writer.record(TARGET, Score.numeric("second", 2));  // takes the one free slot
            writer.record(TARGET, Score.numeric("third", 3));   // dropped
            writer.record(TARGET, Score.numeric("fourth", 4));  // dropped
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(writer.droppedScores() - droppedBefore).isEqualTo(2);
            // The stub is holding its response for up to ten seconds. A writer that waited
            // for room would have spent that inside the caller's turn, which is the whole
            // reason the queue is bounded and lossy rather than bounded and blocking.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        } finally {
            held.countDown();
            // Cleanup only: let the survivor land before the next test resets the stub, so
            // a late arrival cannot be mistaken for that test's own score. Swallowed on
            // purpose — a cleanup timeout must never replace the assertion that failed.
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> scores.receivedCount() == 2);
            } catch (RuntimeException cleanupTimedOut) {
                // the assertions above own this test's verdict
            }
        }
    }

    @Test
    @DisplayName("the consumer survives a rejected write and keeps sending")
    void aRejectedWriteDoesNotStopTheConsumer() {
        writer.record(TARGET, Score.numeric(StubLangfuseScoresController.REJECTED_SCORE_NAME, 1));
        assertThat(scores.awaitNext().body())
                .containsEntry("name", StubLangfuseScoresController.REJECTED_SCORE_NAME);

        // A consumer that died on the 500 above would take every later score with it, and
        // nothing in the application would report it — the queue would simply fill and
        // start dropping, which looks like a capacity problem.
        writer.record(TARGET, Score.numeric("after_rejection", 2));
        assertThat(scores.awaitNext().body()).containsEntry("name", "after_rejection");
    }

    @Test
    @DisplayName("recording a score cannot throw when Langfuse is unreachable")
    void anUnreachableBackendCannotBreakTheCaller() {
        // Port 1 has nothing listening, so every write is refused at connect. The point is
        // that the caller cannot tell: recording is an offer to a queue and nothing else.
        try (var offline = ApplicationContext.run(configuration("http://localhost:1"))) {
            var writerWithNoBackend = offline.getBean(LangfuseScoreWriter.class);

            assertThatCode(() -> {
                for (int i = 0; i < 5; i++) {
                    writerWithNoBackend.record(TARGET, Score.numeric("unreachable", i));
                }
            }).doesNotThrowAnyException();
        }
    }

    private static Map<String, Object> configuration(String langfuseHost) {
        Map<String, Object> config = new HashMap<>();
        // A real SDK exporting into memory, so nothing in this test dials a collector.
        config.put("agentic.test.record-spans", "true");
        config.put("agentic.llm.credentials.google-api-key", "fake");
        config.put("agentic.llm.credentials.openai-api-key", "fake");
        config.put("agentic.observability.environment", "test-env");
        config.put("agentic.observability.langfuse.host", langfuseHost);
        config.put("agentic.observability.langfuse.public-key", PUBLIC_KEY);
        config.put("agentic.observability.langfuse.secret-key", SECRET_KEY);
        config.put("agentic.observability.langfuse.scores.queue-capacity", 1);
        config.put("agentic.observability.langfuse.scores.timeout", "5s");
        return config;
    }
}
