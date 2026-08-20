package io.github.rodrigorjsf.agenticchat.voice;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.AccessorsStyle;
import io.micronaut.core.bind.annotation.Bindable;

import java.util.List;

/**
 * Where the voice document lives and the parts of it a machine checks.
 *
 * <p>The emoji allow-list is configuration rather than a Java constant for the same
 * reason the model prices are: it is the part of the document most likely to be
 * transcribed wrong, and a wrong entry makes the guardrail reject correct answers.
 * In configuration that is a fix; in a constant it is a release.
 */
@ConfigurationProperties("agentic.voice")
@AccessorsStyle(readPrefixes = "")
public interface VoiceProperties {

    /**
     * Classpath location of the voice document. The whole file is placed into the
     * system prompt verbatim — it is a contract text, not a template.
     */
    @Bindable(defaultValue = "voice/VOICE.md")
    String document();

    /**
     * The only emoji the assistant may emit. Anything outside this set is removed
     * from the answer before it is delivered.
     */
    List<String> allowedEmoji();

    /**
     * Ceiling on a single run of bullet points.
     */
    @Bindable(defaultValue = "5")
    int maxBulletItems();

    /**
     * How many times a turn may be sent back to the model for a voice violation the
     * repairs could not fix.
     *
     * <p>Bounded deliberately: LangChain4j's guardrail executor throws once its own
     * retry budget is exhausted, so an unbounded reprompt would turn a stray second
     * emoji into a 500. Past this budget the answer is repaired and delivered, and
     * whatever could not be repaired is counted rather than withheld.
     */
    @Bindable(defaultValue = "1")
    int maxReprompts();
}
