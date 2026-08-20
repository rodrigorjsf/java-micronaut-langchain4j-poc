package io.github.rodrigorjsf.agenticchat.voice;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The tone-of-voice document, loaded once from the classpath.
 *
 * <h2>Why it is in the system prompt and not behind a skill</h2>
 * <p>
 * A skill exists to disclose something the model needs on <em>some</em> turns. This
 * document applies to every answer the assistant ever produces, so routing buys
 * nothing and costs three things: a round trip for {@code activate_skill}, a body
 * that lands in chat memory as a tool result and is then replayed into every later
 * prompt at full price, and a silent failure mode — a turn where the model does not
 * recognise that the skill applies looks exactly like a turn that correctly needed
 * none, and the answer simply comes back in the wrong voice.
 *
 * <p>In the system prompt the same bytes are process-constant, so they sit inside
 * the cacheable prefix and are billed as cached input on every turn after the first
 * — the rate this project already records as {@code cached-input-per-million}.
 *
 * <h2>Why it is last in the prompt</h2>
 * <p>
 * Position is the part of the concern that is real. These rules govern the moment
 * of writing the answer, and everything above them — role, security rules, the skills
 * index — governs the moments before it. Placing the document last makes it the final
 * instruction the model reads before the conversation begins, without moving a single
 * byte out of the cacheable prefix.
 *
 * <h2>Why it is a resource and not a Java text block</h2>
 * <p>
 * The document is written and revised by whoever owns the brand's voice, not by
 * whoever owns this code. As a classpath resource it is reviewed as prose, diffed as
 * prose, and swapped by editing one file. Nothing in Java knows what it says.
 */
@Singleton
public class VoiceProfile {

    private static final Logger LOG = LoggerFactory.getLogger(VoiceProfile.class);

    private final String document;

    public VoiceProfile(VoiceProperties properties) {
        this.document = read(properties.document());
        LOG.info("Voice profile '{}' loaded: {} chars, ~{} tokens",
                properties.document(), document.length(), document.length() / 4);
    }

    /**
     * The document, verbatim. Trailing whitespace is trimmed so the containment check
     * in {@link #presentIn(String)} is not defeated by an editor adding a newline.
     */
    public String document() {
        return document;
    }

    /**
     * Whether the assembled prompt really carries this document.
     *
     * <p>This is the activation guarantee. A tone document that silently fails to
     * reach the model produces answers that are fluent, plausible and in the wrong
     * voice — the failure that no log line reports. Asserting containment at startup
     * turns it into a process that does not start.
     */
    public boolean presentIn(String prompt) {
        return prompt != null && prompt.contains(document);
    }

    private static String read(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Voice document not found on the classpath: " + resource);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (text.isBlank()) {
                throw new IllegalStateException("Voice document is empty: " + resource);
            }
            return text;
        } catch (IOException cannotRead) {
            throw new IllegalStateException("Voice document could not be read: " + resource, cannotRead);
        }
    }
}
