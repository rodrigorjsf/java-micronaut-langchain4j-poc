package io.github.rodrigorjsf.agenticchat.triage;

import io.github.rodrigorjsf.agenticchat.skills.SkillCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four points where the pipeline answers a turn without asking the judge.
 *
 * <p>Language detection lives <em>in</em> the judge, so every one of these paths has
 * to establish the language itself or invent one. All four used to invent
 * {@code pt-BR}: "hello" was matched by the greeting pre-filter, labelled Portuguese
 * and answered in Portuguese, and 12 000 characters of English were declined with
 * "Essa mensagem é longa demais…". The English template existed and was unreachable.
 *
 * <p>The tag is not advisory on the path that matters. {@code RefusalTemplates}
 * selects the refusal by it and that text goes to the user unmediated, so a wrong tag
 * there is the answer. In the prompt it is a hint the message outranks by design
 * ({@code SystemPromptBuilder}: "where it and the message disagree, the message
 * wins"), which is why these tests assert the tag itself rather than the prose a
 * model might have written around it.
 */
class TriageServiceTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();

    /**
     * A judge that fails the test if it is ever consulted. Every case here is one the
     * pre-filter is supposed to answer on its own, and "the judge was called" is the
     * failure the pre-filter exists to prevent.
     */
    private TriageService serviceWithoutAJudge() {
        return serviceWhoseJudge(text -> {
            throw new AssertionError("the pre-filter should have answered this turn: " + text);
        });
    }

    private TriageService serviceWhoseJudge(java.util.function.Function<String, TriageVerdict> judge) {
        var cached = new CachedTriageJudge(
                (text, skills) -> judge.apply(text), new SkillCatalog(List.of()), meters);
        // A no-op score writer: this test is about the triage decision, and the scores it
        // emits have their own test in JudgeScoreTest.
        return new TriageService(cached, meters,
                new io.github.rodrigorjsf.agenticchat.observability.trace.NoOpScoreWriter());
    }

    // ------------------------------------------------------------------ greetings

    @ParameterizedTest(name = "\"{0}\" is greeted in {1}")
    @CsvSource({
            "bom dia, pt-BR",
            "oi, pt-BR",
            "opa, pt-BR",
            "obrigado, pt-BR",
            "valeu, pt-BR",
            "tchau, pt-BR",
            "hello, en",
            "hi, en",
            "hey, en",
            "thanks, en",
            "thank you, en",
            "bye, en",
            "hola, es",
    })
    @DisplayName("a greeting is answered in the language of the greeting, with no model call")
    void greetingsCarryTheirOwnLanguage(String message, String expected) {
        var verdict = serviceWithoutAJudge().triage(message);

        assertThat(verdict.intent()).isEqualTo(TriageVerdict.Intent.GREETING);
        assertThat(verdict.language())
                .as("the greeting vocabulary is already partitioned by language — this is a "
                        + "lookup, not detection")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("punctuation and accents do not lose the greeting's language")
    void greetingsAreMatchedAfterFolding() {
        assertThat(serviceWithoutAJudge().triage("Olá!").language()).isEqualTo("pt-BR");
        assertThat(serviceWithoutAJudge().triage("Hello!!").language()).isEqualTo("en");
    }

    // ------------------------------------------------------------------ too long

    @Test
    @DisplayName("an over-long English message is declined in English")
    void anOverLongMessageIsReadForItsLanguage() {
        var verdict = serviceWithoutAJudge().triage(englishOfAtLeast(13_000));

        assertThat(verdict.intent()).isEqualTo(TriageVerdict.Intent.TOO_LONG);
        assertThat(verdict.language())
                .as("12 000 characters is abundant evidence; nothing here has to be guessed")
                .isEqualTo("en");
    }

    @Test
    void anOverLongPortugueseMessageStaysInPortuguese() {
        var verdict = serviceWithoutAJudge().triage(portugueseOfAtLeast(13_000));

        assertThat(verdict.intent()).isEqualTo(TriageVerdict.Intent.TOO_LONG);
        assertThat(verdict.language()).isEqualTo("pt-BR");
    }

    // ------------------------------------------------------------------ empty

    @Test
    @DisplayName("an empty message defaults to Portuguese because it carries no evidence")
    void anEmptyMessageKeepsTheDocumentedDefault() {
        // The one honest default in this class. There is no text to read, so there is
        // nothing to detect; pt-BR is this assistant's audience and the choice is
        // documented in TriageService rather than left looking like the oversight the
        // other three were.
        var verdict = serviceWithoutAJudge().triage("   ");

        assertThat(verdict.intent()).isEqualTo(TriageVerdict.Intent.EMPTY);
        assertThat(verdict.language()).isEqualTo("pt-BR");
    }

    // ------------------------------------------------------------------ fail open

    @Test
    @DisplayName("a turn escalated by a failing judge keeps the language of its own text")
    void theFailOpenPathReadsTheMessageItAlreadyHas() {
        var service = serviceWhoseJudge(text -> {
            throw new IllegalStateException("judge unavailable");
        });

        var verdict = service.triage("what is the postal code for avenida paulista in sao paulo");

        assertThat(verdict.intent()).isEqualTo(TriageVerdict.Intent.UNKNOWN);
        assertThat(verdict.language()).isEqualTo("en");
    }

    @Test
    void theFailOpenPathFallsBackToPortugueseWhenTheTextSaysNothing() {
        var service = serviceWhoseJudge(text -> {
            throw new IllegalStateException("judge unavailable");
        });

        assertThat(service.triage("01310-200").language()).isEqualTo("pt-BR");
    }

    // ------------------------------------------------------------------ routing

    @Test
    @DisplayName("a greeting with a request after it still reaches the judge")
    void onlyWholeMessageGreetingsArePreFiltered() {
        var service = serviceWhoseJudge(text -> new TriageVerdict(
                TriageVerdict.Decision.IN_SCOPE, 0.99, TriageVerdict.Intent.DATA_REQUEST,
                "pt-BR", "", List.of()));

        assertThat(service.triage("oi, qual o cep da avenida paulista 1578").intent())
                .isEqualTo(TriageVerdict.Intent.DATA_REQUEST);
    }

    private static String englishOfAtLeast(int chars) {
        return repeatUntil("The postal code of that address in the city is not something I "
                + "can find without the street name, and I would like you to explain what "
                + "the difference is between these two records that were returned. ", chars);
    }

    private static String portugueseOfAtLeast(int chars) {
        return repeatUntil("O código postal desse endereço na cidade não é uma coisa que eu "
                + "consigo achar sem o nome da rua, e eu queria que você explicasse qual é a "
                + "diferença entre esses dois registros que foram devolvidos. ", chars);
    }

    private static String repeatUntil(String seed, int chars) {
        return seed.repeat(chars / seed.length() + 1);
    }
}
