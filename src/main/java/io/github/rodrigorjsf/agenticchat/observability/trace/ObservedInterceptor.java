package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.Arrays;

/**
 * Turns {@link Observed} into an observation around the call.
 *
 * <p>The observation is closed on every path, including the failing one, because a span
 * that is never ended is never exported — a hole in the trace rather than an error in it.
 */
@Singleton
@InterceptorBean(Observed.class)
public class ObservedInterceptor implements MethodInterceptor<Object, Object> {

    private final AgentTracer tracer;
    private final ObservationContentPolicy content;

    public ObservedInterceptor(AgentTracer tracer, ObservationContentPolicy content) {
        this.tracer = tracer;
        this.content = content;
    }

    @Override
    @Nullable
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        var annotation = context.getAnnotation(Observed.class);
        String name = annotation.stringValue().filter(value -> !value.isBlank())
                .orElseGet(context::getMethodName);
        var type = annotation.enumValue("type", ObservationType.class).orElse(ObservationType.SPAN);

        try (var observation = tracer.start(name, type)) {
            if (annotation.booleanValue("captureArguments").orElse(false)) {
                observation.input(content.capture(argumentsOf(context)));
            }
            try {
                Object result = context.proceed();
                if (annotation.booleanValue("captureResult").orElse(false)) {
                    observation.output(content.capture(result));
                }
                return result;
            } catch (RuntimeException e) {
                observation.failed(e);
                throw e;
            }
        }
    }

    /**
     * One argument is written as itself; several are written as a list, because a
     * positional list is the only honest shape when the parameter names may not have
     * survived compilation.
     */
    private static Object argumentsOf(MethodInvocationContext<Object, Object> context) {
        Object[] values = context.getParameterValues();
        return values.length == 1 ? values[0] : Arrays.asList(values);
    }
}
