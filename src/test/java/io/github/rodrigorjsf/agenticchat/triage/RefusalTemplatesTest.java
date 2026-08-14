package io.github.rodrigorjsf.agenticchat.triage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refusal is the worst moment to improvise, so the words are asserted here
 * rather than judged after the fact.
 */
class RefusalTemplatesTest {

    private final RefusalTemplates templates = new RefusalTemplates();

    @ParameterizedTest(name = "every intent has a refusal: {0}")
    @EnumSource(TriageVerdict.Intent.class)
    void selectionIsTotal(TriageVerdict.Intent intent) {
        // Selection must never fall through: an intent with no template would reach
        // the user as an empty reply.
        assertThat(templates.refusalFor(intent, "pt-BR")).isNotBlank();
        assertThat(templates.refusalFor(intent, "en")).isNotBlank();
    }

    @Test
    void answersInThePrimaryLanguageOfTheTag() {
        assertThat(templates.refusalFor(TriageVerdict.Intent.CODE_REQUEST, "en")).contains("code");
        assertThat(templates.refusalFor(TriageVerdict.Intent.CODE_REQUEST, "en-US")).contains("code");
        assertThat(templates.refusalFor(TriageVerdict.Intent.CODE_REQUEST, "pt-BR")).contains("código");
        assertThat(templates.refusalFor(TriageVerdict.Intent.CODE_REQUEST, "pt")).contains("código");
    }

    @Test
    @DisplayName("an unknown language falls back to Portuguese, this assistant's audience")
    void anUnknownLanguageFallsBackToPortuguese() {
        assertThat(templates.refusalFor(TriageVerdict.Intent.OFF_TOPIC, "fr"))
                .isEqualTo(templates.refusalFor(TriageVerdict.Intent.OFF_TOPIC, "pt-BR"));
        assertThat(templates.refusalFor(TriageVerdict.Intent.OFF_TOPIC, null)).isNotBlank();
    }

    @ParameterizedTest(name = "offers an alternative: {0}")
    @EnumSource(TriageVerdict.Intent.class)
    void everyRefusalOffersSomethingTheAssistantCanDo(TriageVerdict.Intent intent) {
        // A refusal that offers nothing reads as a wall. The exceptions are the two
        // that are not really refusals — an empty message and an over-long one — where
        // the next step is obvious and naming capabilities would be noise.
        if (intent == TriageVerdict.Intent.EMPTY || intent == TriageVerdict.Intent.TOO_LONG) {
            return;
        }
        var pt = templates.refusalFor(intent, "pt-BR");
        assertThat(pt)
                .as("refusal for %s", intent)
                .containsAnyOf("Posso", "posso", "CEP", "dados públicos");
    }

    @ParameterizedTest(name = "stays short: {0}")
    @EnumSource(TriageVerdict.Intent.class)
    void refusalsAreShort(TriageVerdict.Intent intent) {
        assertThat(templates.refusalFor(intent, "pt-BR")).hasSizeLessThan(220);
        assertThat(templates.refusalFor(intent, "en")).hasSizeLessThan(220);
    }

    @ParameterizedTest(name = "does not lecture: {0}")
    @EnumSource(TriageVerdict.Intent.class)
    void refusalsDoNotLectureOrOverApologise(TriageVerdict.Intent intent) {
        for (String language : List.of("pt-BR", "en")) {
            var text = templates.refusalFor(intent, language).toLowerCase(java.util.Locale.ROOT);
            assertThat(text)
                    .as("refusal for %s in %s", intent, language)
                    .doesNotContain("desculpe, mas desculpe")
                    .doesNotContain("política")
                    .doesNotContain("policy")
                    .doesNotContain("as an ai")
                    .doesNotContain("como uma ia")
                    .doesNotContain("infelizmente");
            // Never asks the user to rephrase something that would be refused again.
            assertThat(text).doesNotContain("reformul");
        }
    }

    @Test
    void aVerdictSelectsItsOwnRefusal() {
        var verdict = new TriageVerdict(TriageVerdict.Decision.OUT_OF_SCOPE, 0.95,
                TriageVerdict.Intent.PROFESSIONAL_ADVICE, "en", "", List.of());

        assertThat(templates.refusalFor(verdict)).contains("professional");
    }
}
