package io.github.rodrigorjsf.agenticchat.triage;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The language of a user turn, established without a model call.
 *
 * <p>Language detection lives in the triage judge, and the whole point of the
 * pre-filter is not to call it. So every turn the pre-filter answers itself has to
 * establish its own language or invent one — and the tag's load-bearing consumer is
 * the refusal path, not the prompt. {@link RefusalTemplates#refusalFor} selects the
 * template by this tag and that text goes to the user with no model in the loop to
 * correct it, which is exactly how 12 000 characters of English came back declined in
 * Portuguese. In the prompt the tag is explicitly a hint the message outranks —
 * {@code SystemPromptBuilder} tells the model "where it and the message disagree, the
 * message wins" — so a wrong tag there is recoverable, and on the refusal path it is
 * not.
 *
 * <h2>Three tiers of evidence, and each path uses the one it has</h2>
 * <ol>
 *   <li><b>A closed vocabulary.</b> {@link #ofGreeting(String)} is a lookup, not
 *       detection. The greeting list was always partitioned by language — "hi" and
 *       "bye" were sitting next to "bom dia" — so the language was already written
 *       down and merely thrown away. Exact, free, and impossible to get wrong on a
 *       two-character message, which is precisely where a detector has nothing to
 *       work with.</li>
 *   <li><b>A stopword ratio</b>, for a message far too long to read but far too long
 *       to be ambiguous either. {@link #detect(String)} is used where the text runs
 *       to thousands of characters, or where the judge failed and the turn is
 *       escalated with its own text in hand.</li>
 *   <li><b>{@link #DEFAULT}</b>, where there is nothing to read at all. An empty
 *       message carries no evidence, and a documented default is the only honest
 *       answer to it.</li>
 * </ol>
 *
 * <h2>Where the ratio is wrong, and it is not a corner case</h2>
 * <p>
 * {@link #detect(String)} counts the whole message, not the part of it the user
 * wrote. A Portuguese turn that pastes an English payload — a stack trace, a log, an
 * error page — is counted together with the payload, and the only thing holding the
 * answer to Portuguese is that the user's own function words outnumber the payload's.
 * A pasted payload is normally far longer than the sentence introducing it, so they
 * do not. Measured on this tree: <i>"Segue o log do erro: ERROR connection refused at
 * server startup, timeout of the request, please check the configuration file and the
 * network settings"</i> answers {@code en}, and
 * {@code MessageLanguageTest.aPortugueseTurnPastingAnEnglishPayloadReadsAsEnglish}
 * pins it rather than leaving it to be discovered. On the {@code TOO_LONG} path —
 * which exists precisely because somebody pasted 12 000 characters — that is a
 * Brazilian user declined with the English template.
 *
 * <p>Separating quoted text from the user's own is a bigger job than this class is,
 * and every message where it would matter and the pre-filter is not answering already
 * reaches the judge. The honest thing is to write the boundary down, which is what
 * that test is for.
 *
 * <h2>Why not a detection library</h2>
 * <p>
 * A CLD3 or Lingua-sized model is tens of megabytes and hundreds of milliseconds of
 * startup for a decision this system makes on two closed sets and one refusal path.
 * The cases that matter here — a fixed greeting vocabulary, and a message of over
 * 12 000 characters — are the two cases where a word list is not the weak option.
 * Anything harder than that already reaches the judge, which reports the language as
 * one field of a verdict it was going to return anyway.
 */
final class MessageLanguage {

    /**
     * Where no evidence exists. Portuguese because this assistant's audience is
     * Brazilian, which is the same reason {@link RefusalTemplates} falls back to it —
     * stated in one place so a later reader can see it was chosen rather than left
     * over.
     */
    static final String DEFAULT = "pt-BR";

    private static final String ENGLISH = "en";
    private static final String SPANISH = "es";

    /**
     * Whole-message greetings, each with the language it is written in.
     *
     * <p>Keys are in the folded form {@link #fold(String)} produces: lowercase, no
     * diacritics, no trailing punctuation. A key that does not survive its own
     * folding — {@code olá} with the accent still on it — is a dead entry that
     * matches nothing, and {@code MessageLanguageTest} fails on it.
     *
     * <p>A message that merely <em>starts</em> with "oi" carries a real request after
     * it and must reach the judge, so the match is on the whole message.
     */
    private static final Map<String, String> GREETINGS = Map.ofEntries(
            Map.entry("oi", DEFAULT),
            Map.entry("ola", DEFAULT),
            Map.entry("opa", DEFAULT),
            Map.entry("eai", DEFAULT),
            Map.entry("e ai", DEFAULT),
            Map.entry("bom dia", DEFAULT),
            Map.entry("boa tarde", DEFAULT),
            Map.entry("boa noite", DEFAULT),
            Map.entry("obrigado", DEFAULT),
            Map.entry("obrigada", DEFAULT),
            Map.entry("valeu", DEFAULT),
            Map.entry("vlw", DEFAULT),
            Map.entry("tchau", DEFAULT),
            Map.entry("ate mais", DEFAULT),

            Map.entry("hi", ENGLISH),
            Map.entry("hello", ENGLISH),
            Map.entry("hey", ENGLISH),
            Map.entry("yo", ENGLISH),
            Map.entry("thanks", ENGLISH),
            Map.entry("thank you", ENGLISH),
            Map.entry("bye", ENGLISH),

            // Spanish resolves to a Portuguese refusal downstream, which is
            // RefusalTemplates' documented choice for a Brazilian-audience assistant.
            // Reporting the tag honestly is still worth it: the agent answers in the
            // user's language, and only the refusal wording is Portuguese-only.
            Map.entry("hola", SPANISH));

    /**
     * A length past which a message cannot be a greeting, so a long one is left alone
     * before the map is touched at all. No room is reserved for punctuation:
     * {@link #ofGreeting} strips it before it compares. The longest key is nine
     * characters ("thank you", "boa tarde"), and the bound is generous on purpose —
     * raising it costs one map lookup, trimming it costs a greeting that stops
     * matching.
     */
    private static final int LONGEST_GREETING = 20;

    /**
     * How much of an over-long message is read. {@code TOO_LONG} means the text is
     * already past 12 000 characters and its length is chosen by whoever sent it, so
     * the scan is bounded rather than proportional. The first 2 000 characters of a
     * 200 000-character message settle the question as well as all of it would.
     */
    private static final int SCAN_LIMIT = 2_000;

    /**
     * Below this many matched stopwords the sample says nothing, and the default is
     * the honest answer. Two is enough on the paths that use this — a message over
     * 12 000 characters that matches fewer than two stopwords of either language is
     * not prose in either.
     */
    private static final int MIN_EVIDENCE = 2;

    private static final Pattern NOT_A_LETTER = Pattern.compile("[^a-z]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[!?.,;:\\s]+$");

    /**
     * Function words, in their folded form, that belong to one of these two languages
     * and not to the other.
     *
     * <p>The exclusions are the load-bearing part. {@code as}, {@code no}, {@code do},
     * {@code me} and every single letter are ordinary words in <em>both</em>
     * languages, and a list containing them scores English prose as Portuguese in
     * proportion to how much English it contains. {@code MessageLanguageTest} asserts
     * the two sets do not intersect, which is a check that only means something
     * because the collisions were removed by hand first.
     */
    private static final Set<String> PORTUGUESE_WORDS = Set.of(
            "nao", "que", "uma", "para", "com", "voce", "por", "mais", "como", "qual",
            "isso", "esta", "sao", "dos", "das", "meu", "minha", "muito", "tem", "ser",
            "fazer", "quero", "preciso", "pode", "sobre", "quando", "onde", "porque",
            "tambem", "ate", "seu", "sua", "esse", "essa", "esses", "aqui", "agora",
            "ainda", "entao", "tudo", "todos", "cada", "entre", "de", "foram", "eu");

    private static final Set<String> ENGLISH_WORDS = Set.of(
            "the", "and", "of", "to", "in", "that", "is", "it", "for", "with", "you",
            "this", "are", "was", "have", "but", "not", "from", "they", "what", "which",
            "would", "can", "please", "about", "my", "need", "want", "how", "why",
            "when", "where", "there", "their", "been", "will", "your", "some", "more",
            "could", "should", "does", "did", "were", "between");

    private MessageLanguage() {
    }

    /**
     * The language of a whole-message greeting, or {@code null} when the message is
     * not one.
     *
     * <p>Returning {@code null} rather than a default is deliberate: the caller has to
     * distinguish "this is a Portuguese greeting" from "this is not a greeting", and a
     * lookup that answered {@code pt-BR} to both is the bug this class exists to fix.
     */
    static String ofGreeting(String normalized) {
        if (normalized == null) {
            return null;
        }
        String stripped = TRAILING_PUNCTUATION.matcher(normalized.strip()).replaceAll("").strip();
        if (stripped.isEmpty() || stripped.length() > LONGEST_GREETING) {
            return null;
        }
        return GREETINGS.get(fold(stripped));
    }

    /**
     * The language of a message long enough to be read for it, or {@link #DEFAULT}
     * when the sample does not separate the two.
     *
     * <p>Deliberately unable to say "I am not sure": every caller has to put a tag in
     * a verdict, so an undecided result and the default are the same answer. What the
     * caller must not do is treat the answer as certain — this is a hint the judge
     * would have made better, on a path where the judge is not being called.
     */
    static String detect(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT;
        }
        String sample = fold(text.length() > SCAN_LIMIT ? text.substring(0, SCAN_LIMIT) : text);
        int portuguese = 0;
        int english = 0;
        for (String token : NOT_A_LETTER.split(sample)) {
            if (PORTUGUESE_WORDS.contains(token)) {
                portuguese++;
            } else if (ENGLISH_WORDS.contains(token)) {
                english++;
            }
        }
        // Only English has to be proven. Portuguese is the default, and the two
        // downstream consumers — the agent's reply_language and RefusalTemplates —
        // know exactly these two languages, so a third one detected here would be
        // answered in Portuguese anyway. The Portuguese words are still counted: they
        // are the counter-evidence that keeps a Portuguese message quoting an English
        // term from being read as English.
        return english > portuguese && english >= MIN_EVIDENCE ? ENGLISH : DEFAULT;
    }

    /**
     * Lowercase, without diacritics. Both sides of every comparison in this class go
     * through it, so "Olá!" and "ola" are the same greeting and "não" counts as
     * {@code nao}.
     */
    static String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return DIACRITICS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
    }

    // ------------------------------------------------------------------ for tests

    static Map<String, String> greetings() {
        return GREETINGS;
    }

    static Set<String> portugueseWords() {
        return PORTUGUESE_WORDS;
    }

    static Set<String> englishWords() {
        return ENGLISH_WORDS;
    }
}
