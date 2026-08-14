package io.github.rodrigorjsf.agenticchat.guardrail.input;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Canonicalises user text so every later check — and the model — sees one
 * unambiguous representation.
 *
 * <p>This is the highest-leverage guardrail component precisely because it never
 * blocks anything. Detection rules that run on unnormalized text can be walked
 * past with fullwidth characters, zero-width joiners or bidi overrides;
 * normalizing first means each rule only has to be written once, against one form.
 *
 * <p>NFKC is the right Unicode form here: it folds fullwidth {@code Ｉｇｎｏｒｅ},
 * mathematical-bold {@code 𝗜𝗴𝗻𝗼𝗿𝗲} and ligatures onto plain ASCII, which defeats
 * the cheapest evasion tricks (https://www.unicode.org/reports/tr15/). It
 * deliberately does <em>not</em> fold Cyrillic or Greek homoglyphs — {@code а}
 * U+0430 stays distinct from {@code a} U+0061. Confusables folding
 * (https://www.unicode.org/reports/tr39/) is a false-positive minefield in a
 * genuinely multilingual application, so homoglyphs are counted as a signal by
 * {@link InjectionHeuristics} rather than silently rewritten.
 */
public final class TextNormalizer {

    /**
     * Zero-width space/joiners, BOM, bidi overrides, soft hyphen, word joiner.
     */
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\u00AD\\u180E\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]");

    /**
     * C0/C1 control characters, keeping tab, newline and carriage return.
     */
    private static final Pattern CONTROLS = Pattern.compile(
            "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]");

    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{4,}");
    private static final Pattern MANY_SPACES = Pattern.compile("[ \\t]{4,}");
    private static final Pattern CRLF = Pattern.compile("\\r\\n?");

    private TextNormalizer() {
    }

    /**
     * @return the canonical form of {@code raw}, or {@code raw} itself when it is
     * already canonical
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String out = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        out = INVISIBLE.matcher(out).replaceAll("");
        out = CRLF.matcher(out).replaceAll("\n");
        out = CONTROLS.matcher(out).replaceAll(" ");
        // Long runs of blank lines and spaces are a padding trick used to push a
        // system prompt out of a model's attention window. Three blank lines is
        // more than any human formats with.
        out = MANY_NEWLINES.matcher(out).replaceAll("\n\n\n");
        out = MANY_SPACES.matcher(out).replaceAll(" ");
        return out.strip();
    }

    /**
     * How many invisible characters {@link #normalize} would remove. Counted before rewriting, since afterwards there are none left to see.
     */
    public static int countInvisible(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        int count = 0;
        var matcher = INVISIBLE.matcher(raw);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
