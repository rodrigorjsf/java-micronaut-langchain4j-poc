package io.github.rodrigorjsf.agenticchat.triage;

import java.util.Set;

/**
 * Test-only bridge to the triage package's language detector, so the scenario suite reads an
 * answer's language with the same stopword ratio the application uses on a user turn — and
 * without making {@link MessageLanguage} public for one test consumer.
 *
 * <p>The detector knows two tags and treats Portuguese as the default, so it can prove English
 * but only fail to disprove Portuguese: a Spanish answer reads as {@code pt-BR} (see #48).
 */
public final class AnswerLanguage {

    /** The only tags the detector can tell apart. */
    public static final Set<String> SUPPORTED = Set.of(MessageLanguage.DEFAULT, "en");

    private AnswerLanguage() {
    }

    public static String of(String text) {
        return MessageLanguage.detect(text);
    }
}
