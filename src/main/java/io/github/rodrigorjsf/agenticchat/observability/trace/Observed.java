package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.aop.Around;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Observes a method as one Langfuse observation.
 *
 * <p>This exists so that the application's own steps — the turn, triage, compaction —
 * are observed without a line of tracing code inside them. Everything downstream of a
 * LangChain4j call is covered by a listener instead; this annotation is for the layers
 * LangChain4j cannot see.
 *
 * <p><b>Self-invocation IS observed here</b>, which is worth stating because the
 * opposite is true of {@code @Cacheable} in this same codebase. Micronaut's
 * {@code @Around} advice is compile-time and subclass-based: the generated subclass
 * overrides the annotated method, so {@code this} inside a sibling method is already the
 * intercepted instance. Measured in {@code ObservedInterceptorTest}. Applying the
 * annotation to a {@code private}, {@code static} or {@code final} method is a
 * compilation error rather than a silent no-op.
 *
 * <p>Capturing arguments and results is off by default. A turn's arguments are the user's
 * message; that decision belongs to {@link ObservationContentPolicy} and to the person
 * annotating the method, not to a default.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Around
public @interface Observed {

    /**
     * The observation name. Defaults to the method name.
     */
    String value() default "";

    ObservationType type() default ObservationType.SPAN;

    /**
     * Writes the method arguments as the observation's input, subject to
     * {@link ObservationContentPolicy}.
     */
    boolean captureArguments() default false;

    /**
     * Writes the return value as the observation's output, subject to
     * {@link ObservationContentPolicy}.
     */
    boolean captureResult() default false;
}
