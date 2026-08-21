package io.github.rodrigorjsf.agenticchat.triage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two closed catalogues this class decides from, and the checks that keep them
 * honest.
 *
 * <p>A word list is only as good as the discipline around it, and both failures it
 * can have are silent. A greeting written with its accent still on it never matches
 * anything, and a language tag written {@code pt_BR} is replaced by
 * {@link TriageVerdict}'s compact constructor with {@code pt-BR} — so the entry
 * appears to work and quietly answers every English greeting in Portuguese, which is
 * the exact bug this class was written to remove.
 */
class MessageLanguageTest {

    // ------------------------------------------------------- the greeting catalogue

    @Test
    @DisplayName("the greeting catalogue runs in both directions")
    void theGreetingCatalogueRunsInBothDirections() {
        // The negative cases first, and deliberately: a liveness check that accepts a
        // dead entry ratifies the catalogue instead of grading it. Each control below
        // is a mistake someone will make when adding the next greeting.
        assertThat(isLive("Olá", "pt-BR"))
                .as("a key still carrying its accent: the lookup folds the message, never the "
                        + "catalogue, so this entry can never be reached").isFalse();
        assertThat(isLive("HELLO", "en")).as("an uppercase key is unreachable for the same reason").isFalse();
        assertThat(isLive("hey there my friend, how are you today", "en"))
                .as("longer than the pre-filter's whole-message window").isFalse();
        assertThat(isLive("hi", "pt_BR"))
                .as("an underscore is not a BCP-47 tag; the verdict replaces it with pt-BR "
                        + "and the entry silently answers English in Portuguese").isFalse();
        assertThat(isLive("hi", "english")).as("not a tag at all").isFalse();
        assertThat(isLive("hi", "en")).as("the control that must pass").isTrue();

        // Direction one: every greeting listed is a greeting the pre-filter matches.
        // Direction two: every tag listed survives the verdict unchanged, so what the
        // catalogue says is what the agent is told to answer in.
        MessageLanguage.greetings().forEach((token, tag) ->
                assertThat(isLive(token, tag))
                        .as("\"%s\" -> %s is a live catalogue entry", token, tag)
                        .isTrue());
    }

    @Test
    @DisplayName("the catalogue covers the greetings the pre-filter used to answer in Portuguese")
    void everyGreetingThatUsedToBeHardcodedIsStillMatched() {
        // The set that shipped before this change, listed here so a later tidy-up
        // cannot quietly shrink the pre-filter and push ordinary greetings onto the
        // judge — which is the saving the pre-filter exists for.
        var shipped = List.of("oi", "ola", "opa", "eai", "e ai", "bom dia", "boa tarde",
                "boa noite", "hi", "hello", "hey", "yo", "hola", "obrigado", "obrigada",
                "valeu", "vlw", "thanks", "thank you", "tchau", "ate mais", "bye");

        assertThat(MessageLanguage.greetings().keySet()).containsAll(shipped);
    }

    @Test
    @DisplayName("a message that only starts with a greeting is not one")
    void aGreetingWithARequestAfterItIsNotAGreeting() {
        assertThat(MessageLanguage.ofGreeting("oi, qual o cep da avenida paulista")).isNull();
        assertThat(MessageLanguage.ofGreeting("hello, what is the postal code")).isNull();
        assertThat(MessageLanguage.ofGreeting("")).isNull();
        assertThat(MessageLanguage.ofGreeting(null)).isNull();
    }

    @Test
    @DisplayName("trailing punctuation and case do not lose a greeting")
    void greetingsAreFoldedBeforeTheLookup() {
        assertThat(MessageLanguage.ofGreeting("Olá!")).isEqualTo("pt-BR");
        assertThat(MessageLanguage.ofGreeting("BOM DIA...")).isEqualTo("pt-BR");
        assertThat(MessageLanguage.ofGreeting("Hey?")).isEqualTo("en");
        assertThat(MessageLanguage.ofGreeting("  hola  ")).isEqualTo("es");
    }

    // ------------------------------------------------------------ the stopword sets

    @Test
    @DisplayName("no word counts as evidence for both languages")
    void theStopwordSetsShareNoWord() {
        // Negative case first. The check has to be able to see a collision, or it is
        // just an assertion that two sets exist.
        assertThat(overlapOf(Map.of("nao", 1, "no", 1).keySet(), Map.of("no", 1, "the", 1).keySet()))
                .as("a planted collision the check must find")
                .containsExactly("no");

        // "as", "no", "do", "me" and every single letter are ordinary words in both
        // languages. A list carrying them scores English prose as Portuguese in
        // proportion to how much English it contains.
        assertThat(overlapOf(MessageLanguage.portugueseWords(), MessageLanguage.englishWords()))
                .as("a word in both lists is evidence for nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("every stopword is written in the folded form the detector produces")
    void theStopwordsAreFolded() {
        // "não" in the list never matches, because the sample is folded to "nao"
        // before the lookup. Same silent failure as an accented greeting.
        for (String word : MessageLanguage.portugueseWords()) {
            assertThat(word).isEqualTo(MessageLanguage.fold(word));
        }
        for (String word : MessageLanguage.englishWords()) {
            assertThat(word).isEqualTo(MessageLanguage.fold(word));
        }
    }

    // ------------------------------------------------------------------- detection

    @Test
    void ordinaryProseIsReadForItsLanguage() {
        assertThat(MessageLanguage.detect(
                "I would like to know what the difference is between these two records"))
                .isEqualTo("en");
        assertThat(MessageLanguage.detect(
                "eu queria saber qual é a diferença entre esses dois registros"))
                .isEqualTo("pt-BR");
    }

    @Test
    @DisplayName("Portuguese quoting an English term is still Portuguese")
    void aFewForeignWordsDoNotFlipTheAnswer() {
        // A loanword is the easy half: "cashback" is not a stopword of either list, so
        // this sample never reaches the comparison at all. Kept because it is the case
        // a reader expects to see, and asserted BEFORE the hard one so the difference
        // between them is visible.
        assertThat(MessageLanguage.detect(
                "preciso saber como funciona o cashback e o que significa no extrato"))
                .isEqualTo("pt-BR");

        // The hard half, and the only sample here that exercises the counter-evidence
        // clause: a Portuguese question quoting an English SENTENCE puts real English
        // stopwords in the sample — "the", "between", "the" — which is three, past
        // MIN_EVIDENCE on its own. Only "english > portuguese" keeps the answer
        // Portuguese, and dropping that clause answers a Brazilian customer in English
        // because he pasted the phrase he was asking about.
        assertThat(MessageLanguage.detect(
                "preciso saber o que significa a frase \"the difference between the two "
                        + "records\" que apareceu no meu extrato"))
                .isEqualTo("pt-BR");
    }

    @Test
    @DisplayName("a Portuguese turn that pastes an English payload reads as English")
    void aPortugueseTurnPastingAnEnglishPayloadReadsAsEnglish() {
        // This pins where the line falls TODAY. It does not say "en" is the right
        // answer — it is not. One Portuguese sentence introduces a pasted English log,
        // and the ratio counts the paste: six English function words ("of", "the" three
        // times, "please", "and") against none at all from the introduction, because
        // "segue", "log" and "erro" are stopwords of neither list. The clause that is
        // supposed to hold the answer down, english > portuguese, only asks the user's
        // own function words to OUTNUMBER the payload's, and a paste is longer than the
        // sentence in front of it. Measured: widening that clause to
        // english > portuguese + 3 leaves this sample answering "en".
        //
        // The cost is concrete and it is on the path the class was written for: on
        // TOO_LONG this is a Brazilian user who pasted a 12 000-character English stack
        // trace being declined with the English template.
        //
        // If a later change to detect() makes this sample answer "pt-BR", this test is
        // red for a GOOD reason. Re-decide it here; do not revert the improvement to
        // keep the assertion.
        assertThat(MessageLanguage.detect(
                "Segue o log do erro: ERROR connection refused at server startup, "
                        + "timeout of the request, please check the configuration file "
                        + "and the network settings"))
                .isEqualTo("en");
    }

    @Test
    @DisplayName("too little evidence resolves to the documented default, not to a guess")
    void aThinSampleFallsBackToPortuguese() {
        assertThat(MessageLanguage.detect("01310-200")).isEqualTo("pt-BR");
        assertThat(MessageLanguage.detect("")).isEqualTo("pt-BR");
        assertThat(MessageLanguage.detect(null)).isEqualTo("pt-BR");
        assertThat(MessageLanguage.detect("cep paulista")).isEqualTo("pt-BR");
    }

    @Test
    @DisplayName("the scan is bounded, because the text that reaches it is attacker-sized")
    void onlyTheHeadOfAnOverLongMessageIsRead() {
        // TOO_LONG means the message is already past 12 000 characters and its length
        // is chosen by whoever sent it. This asserts the bound rather than trusting
        // it: 2 000 characters of English followed by 200 000 of Portuguese answers
        // "en", which only happens if the tail was never read.
        String head = "what is the difference between these two records that you have ".repeat(40);
        String tail = "e eu queria saber qual e a diferenca entre esses dois registros ".repeat(3_200);

        assertThat(head.length()).isGreaterThanOrEqualTo(2_000);
        assertThat(MessageLanguage.detect(head + tail)).isEqualTo("en");
    }

    /**
     * A catalogue entry is live when the pre-filter really matches the token, and the
     * tag really survives into the verdict the agent is handed.
     */
    private static boolean isLive(String token, String tag) {
        // The key has to BE the folded form, not merely fold to it: ofGreeting folds
        // the incoming message and then looks it up, so a key that is not already
        // folded is a row nothing can ever match.
        boolean reachableKey = token.equals(MessageLanguage.fold(token))
                && token.equals(token.strip());
        boolean matched = reachableKey && tag.equals(MessageLanguage.ofGreeting(token));
        boolean tagSurvives = tag.equals(TriageVerdict.deterministic(
                TriageVerdict.Decision.IN_SCOPE, TriageVerdict.Intent.GREETING, tag).language());
        return matched && tagSurvives;
    }

    private static TreeSet<String> overlapOf(java.util.Set<String> left, java.util.Set<String> right) {
        var shared = new TreeSet<>(left);
        shared.retainAll(right);
        return shared;
    }
}
