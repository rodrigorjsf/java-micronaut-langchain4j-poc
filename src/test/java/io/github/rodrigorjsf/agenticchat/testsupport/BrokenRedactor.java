package io.github.rodrigorjsf.agenticchat.testsupport;

import io.github.rodrigorjsf.agenticchat.observability.trace.ContentRedactor;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * A redactor that throws, because a real one is deployment-supplied code running inside a
 * method it was only supposed to watch.
 */
@Singleton
@Requires(property = "agentic.test.broken-redactor", value = "true")
public class BrokenRedactor implements ContentRedactor {

    @Override
    public Object redact(Object value) {
        throw new IllegalStateException("this redactor is broken on purpose");
    }
}
