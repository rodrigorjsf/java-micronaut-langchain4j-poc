package io.github.rodrigorjsf.agenticchat.api;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * What the assistant can do, in the same words the model sees.
 *
 * <p>Served from the skill catalogue rather than a hand-written list, so the
 * documented capabilities cannot drift away from the real ones.
 */
@Serdeable
public record CapabilitiesResponse(List<String> skills) {
}
