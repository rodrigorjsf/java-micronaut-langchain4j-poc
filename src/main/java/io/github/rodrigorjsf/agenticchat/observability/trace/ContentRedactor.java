package io.github.rodrigorjsf.agenticchat.observability.trace;

/**
 * Removes what must not leave the process on an observation.
 *
 * <p>Contribute a bean per rule. Returning {@code null} drops the payload entirely, which
 * is the right answer when a redactor cannot establish that a value is safe rather than
 * merely failing to find a pattern in it.
 */
@FunctionalInterface
public interface ContentRedactor {

    Object redact(Object value);
}
