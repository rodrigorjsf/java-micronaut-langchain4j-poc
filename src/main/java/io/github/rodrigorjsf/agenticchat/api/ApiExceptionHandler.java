package io.github.rodrigorjsf.agenticchat.api;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns anything unhandled into a shaped error.
 *
 * <p>The body never carries the exception message. A stack trace or a driver error
 * reaching an HTTP client tells an attacker what is behind the endpoint, and in
 * this application an exception message can carry an upstream URL or a fragment of
 * a prompt. The real cause goes to the log with the correlation id.
 */
@Produces
@Singleton
@Requires(classes = {RuntimeException.class, ExceptionHandler.class})
public class ApiExceptionHandler implements ExceptionHandler<RuntimeException, HttpResponse<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Serdeable
    public record ApiError(String error, String message) {
    }

    @Override
    public HttpResponse<?> handle(HttpRequest request, RuntimeException exception) {
        if (exception instanceof IllegalArgumentException) {
            LOG.info("Rejected a request: {}", exception.getMessage());
            return HttpResponse.badRequest(new ApiError("invalid_request", exception.getMessage()));
        }
        LOG.error("Unhandled failure on {} {}", request.getMethod(), request.getPath(), exception);
        return HttpResponse.<ApiError>status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "The request could not be completed."));
    }
}
