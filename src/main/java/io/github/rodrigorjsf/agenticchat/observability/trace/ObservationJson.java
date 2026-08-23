package io.github.rodrigorjsf.agenticchat.observability.trace;

import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Turns an observation's input or output into the JSON string Langfuse stores.
 *
 * <p>The contract with the seams is deliberately narrow: pass a {@code String}, a
 * number, a boolean, a {@code Map} or a {@code List} of those. Micronaut Serde is a
 * compile-time serializer, so an arbitrary domain object it has never been told about
 * fails at runtime rather than at build time — and a failure here must never be able to
 * break the call it was only observing. Anything it refuses is written as its
 * {@code toString}, and the reason is logged once per call at debug.
 */
@Singleton
public class ObservationJson {

    private static final Logger LOG = LoggerFactory.getLogger(ObservationJson.class);

    private final JsonMapper mapper;

    public ObservationJson(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * For tests and for any caller outside the bean container.
     */
    public static ObservationJson compact() {
        return new ObservationJson(JsonMapper.createDefault());
    }

    /**
     * @return the JSON form, or {@code null} when there is nothing to write — the
     * caller then writes no attribute at all rather than the string "null"
     */
    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException | RuntimeException e) {
            LOG.debug("Falling back to toString for observation payload of type {}",
                    value.getClass().getName(), e);
            return '"' + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
    }
}
