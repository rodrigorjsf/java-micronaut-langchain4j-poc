package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.context.ApplicationContext;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The AOP seam: a method observed without a line of tracing code inside it.
 */
class ObservedInterceptorTest {

    @Singleton
    static class Subject {

        @Observed(value = "triage", type = ObservationType.CHAIN, captureArguments = true, captureResult = true)
        String classify(String message) {
            return "IN_SCOPE";
        }

        @Observed
        void withDefaults() {
        }

        @Observed("explodes")
        void fails() {
            throw new IllegalStateException("upstream refused");
        }

        @Observed(value = "outer", type = ObservationType.AGENT)
        String outer() {
            return classify("from inside");
        }
    }

    private ApplicationContext ctx;
    private InMemorySpanExporter exported;
    private Subject subject;

    @BeforeEach
    void setUp() {
        ctx = ApplicationContext.run(Map.of(
                "agentic.test.record-spans", "true",
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake"));
        exported = ctx.getBean(InMemorySpanExporter.class);
        subject = ctx.getBean(Subject.class);
        exported.reset();
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Test
    @DisplayName("an annotated method becomes an observation of the declared type")
    void anAnnotatedMethodIsObserved() {
        subject.classify("bom dia");

        assertThat(exported.getFinishedSpanItems()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("triage");
            assertThat(span.getAttributes().asMap())
                    .containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "chain")
                    .containsEntry(LangfuseAttributes.OBSERVATION_INPUT, "\"bom dia\"")
                    .containsEntry(LangfuseAttributes.OBSERVATION_OUTPUT, "\"IN_SCOPE\"");
        });
    }

    @Test
    @DisplayName("without a name the observation takes the method's name, and captures nothing")
    void defaultsAreTheMethodNameAndNoContent() {
        subject.withDefaults();

        var span = exported.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("withDefaults");
        assertThat(span.getAttributes().asMap()).containsEntry(LangfuseAttributes.OBSERVATION_TYPE, "span");
        assertThat(span.getAttributes().asMap().keySet().stream().map(Object::toString))
                .doesNotContain(LangfuseAttributes.OBSERVATION_INPUT.getKey());
    }

    @Test
    @DisplayName("a throwing method still exports its observation, at ERROR")
    void aFailureIsExportedNotLost() {
        assertThatThrownBy(subject::fails).isInstanceOf(IllegalStateException.class);

        assertThat(exported.getFinishedSpanItems()).singleElement().satisfies(span ->
                assertThat(span.getAttributes().asMap())
                        .containsEntry(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR")
                        .containsEntry(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, "upstream refused"));
    }

    @Test
    @DisplayName("a self-invoked annotated method IS observed, and nests under its caller")
    void selfInvocationIsStillObserved() {
        subject.outer();

        // Measured, not assumed. Micronaut's @Around advice is compile-time and
        // subclass-based: the generated subclass overrides both methods, so `this` inside
        // outer() is the intercepted instance and the inner call goes through the advice.
        // That is the opposite of the JDK-proxy behaviour this codebase records for
        // @Cacheable, and it is pinned here because the two annotations look identical at
        // the call site.
        assertThat(exported.getFinishedSpanItems())
                .extracting(io.opentelemetry.sdk.trace.data.SpanData::getName)
                .containsExactly("triage", "outer");

        var spans = exported.getFinishedSpanItems();
        var inner = spans.getFirst();
        var outer = spans.getLast();
        assertThat(inner.getParentSpanId()).isEqualTo(outer.getSpanId());
    }

    @Test
    @DisplayName("with content capture off, nothing the user typed reaches the span")
    void captureCanBeSwitchedOff() {
        try (var quiet = ApplicationContext.run(Map.of(
                "agentic.test.record-spans", "true",
                "agentic.observability.capture-content", "false",
                "agentic.llm.credentials.google-api-key", "fake",
                "agentic.llm.credentials.openai-api-key", "fake"))) {

            var recorder = quiet.getBean(InMemorySpanExporter.class);
            recorder.reset();
            quiet.getBean(Subject.class).classify("meu CPF é 000.000.000-00");

            var keys = recorder.getFinishedSpanItems().getFirst().getAttributes().asMap()
                    .keySet().stream().map(Object::toString).toList();
            assertThat(keys).doesNotContain(
                    LangfuseAttributes.OBSERVATION_INPUT.getKey(),
                    LangfuseAttributes.OBSERVATION_OUTPUT.getKey());
        }
    }
}
