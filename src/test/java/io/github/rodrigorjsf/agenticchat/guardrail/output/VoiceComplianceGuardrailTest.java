package io.github.rodrigorjsf.agenticchat.guardrail.output;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.rodrigorjsf.agenticchat.voice.VoiceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules here are the ones a machine can decide. Everything the voice document
 * says about warmth, empathy or being didactic is deliberately absent: a check that
 * cannot separate a compliant answer from a violating one is not a check.
 *
 * <p>The negative cases carry as much weight as the positive ones. A guardrail that
 * fires on {@code você} because the document bans {@code -e} endings is switched off
 * within a week, and then none of the real rules are enforced either.
 */
class VoiceComplianceGuardrailTest {

    private static final List<String> ALLOWED = List.of("❗", "💡", "📱", "💻", "😊", "⏰", "📅", "⚠", "💛", "🔑", "🌎");

    private final VoiceComplianceGuardrail guardrail =
            new VoiceComplianceGuardrail(properties(1), new SimpleMeterRegistry());

    // ------------------------------------------------------------------- emoji

    @Test
    @DisplayName("a single allowed emoji at the end of the answer is compliant")
    void oneTrailingEmojiPasses() {
        assertThat(guardrail.violations("O CEP é 01310-100. 😊")).isEmpty();
    }

    @Test
    @DisplayName("no emoji at all is compliant — the document says to skip it when in doubt")
    void noEmojiPasses() {
        assertThat(guardrail.violations("O CEP da Avenida Paulista é 01310-100.")).isEmpty();
    }

    @Test
    void twoEmojiAreReportedAndRepairedToTheLastAllowedOne() {
        String answer = "Bom dia 😊 o CEP é 01310-100. 💡";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("APENAS 1 emoji"));
        assertThat(guardrail.violations(guardrail.repair(answer))).isEmpty();
        assertThat(guardrail.repair(answer)).endsWith("💡").doesNotContain("😊");
    }

    @Test
    void anEmojiOutsideTheAllowListIsRemoved() {
        String answer = "Consegui encontrar o dado. 🚀";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("não está na lista permitida"));
        assertThat(guardrail.repair(answer)).isEqualTo("Consegui encontrar o dado.");
    }

    @Test
    @DisplayName("an emoji in the middle is moved to the end rather than deleted")
    void misplacedEmojiIsMovedToTheEnd() {
        String answer = "O prazo ⏰ é de dois dias úteis.";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("ao final da mensagem"));
        assertThat(guardrail.repair(answer)).isEqualTo("O prazo é de dois dias úteis. ⏰");
    }

    @Test
    @DisplayName("the variation selector is not a second emoji")
    void variationSelectorDoesNotDoubleTheCount() {
        // U+26A0 U+FE0F is what most models emit; the allow-list holds the bare
        // U+26A0. Both must read as the one warning sign a person sees.
        assertThat(guardrail.violations("Instabilidade no sistema. ⚠️")).isEmpty();
    }

    // ------------------------------------------------------- inclusive language

    @Test
    void statedNeologismsAreReplacedWithTheFormTheDocumentGives() {
        String answer = "Obrigade! Todes podem acessar.";

        assertThat(guardrail.violations(answer)).hasSize(2);
        assertThat(guardrail.repair(answer)).isEqualTo("Obrigado! Todos podem acessar.");
    }

    @Test
    void theAtSignFormIsMatchedToo() {
        assertThat(guardrail.repair("Tod@s podem acessar.")).isEqualTo("Todos podem acessar.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Você pode acessar pelo site.",
            "Envie um e-mail com o comprovante.",
            "O saldo está disponível onde você preferir.",
            "O status aparece em verde quando confirma.",
            "Confira o extrato online.",
            "Este é o valor de hoje."})
    @DisplayName("ordinary words ending in -e are not neologisms")
    void ordinaryPortugueseIsNotFlagged(String answer) {
        assertThat(guardrail.violations(answer)).isEmpty();
    }

    // ------------------------------------------------------------ forbidden terms

    @Test
    void theBrandNameIsCorrectedRatherThanReported() {
        assertThat(guardrail.repair("O Banco Inter oferece esse produto."))
                .isEqualTo("O Inter oferece esse produto.");
        assertThat(guardrail.repair("Você encontra no Inter Bank."))
                .isEqualTo("Você encontra no Inter.");
    }

    @Test
    void investmentVerbsAreReportedAndNotRepairable() {
        String answer = "Recomendo esse fundo para o seu perfil.";

        assertThat(guardrail.violations(answer)).anyMatch(rule -> rule.contains("recomendo"));
        assertThat(guardrail.violations(guardrail.repair(answer))).isNotEmpty();
    }

    @Test
    void capacitistTermsAreReported() {
        assertThat(guardrail.violations("Veja mais no aplicativo."))
                .anyMatch(rule -> rule.contains("capacitista"));
        assertThat(guardrail.violations("Tudo na palma da mão."))
                .anyMatch(rule -> rule.contains("capacitista"));
    }

    @Test
    void regionalismsAreMatchedAsWholeWordsOnly() {
        assertThat(guardrail.violations("Uai, deu certo.")).anyMatch(rule -> rule.contains("regionalismo"));
        // "uai" inside another word is not a regionalism, and neither is a company
        // name that happens to contain it.
        assertThat(guardrail.violations("A empresa Guaiba Ltda está ativa.")).isEmpty();
    }

    // -------------------------------------------------------------------- lists

    @Test
    void aSixthBulletIsReported() {
        String answer = "Documentos aceitos:\n- RG\n- CPF\n- CNH\n- Passaporte\n- Título\n- Carteira";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("máximo de 5 bullet points"));
    }

    @Test
    void fiveBulletsAreFine() {
        String answer = "Documentos aceitos:\n- RG\n- CPF\n- CNH\n- Passaporte\n- Título";

        assertThat(guardrail.violations(answer)).isEmpty();
    }

    @Test
    @DisplayName("numbered step-by-step instructions carry no cap")
    void numberedListsAreNotCapped() {
        String answer = "Passo a passo:\n1. Abra\n2. Toque\n3. Escolha\n4. Confirme\n5. Aguarde\n6. Pronto";

        assertThat(guardrail.violations(answer)).isEmpty();
    }

    // ------------------------------------------------------------ the full path

    @Test
    @DisplayName("a repairable violation is fixed in place, with no model call")
    void repairableViolationsNeverReprompt() {
        var parameters = new InvocationParameters();
        var result = guardrail.validate(requestFor("Todes podem acessar. 😊 💡", parameters));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.successfulText()).isEqualTo("Todos podem acessar. 💡");
        assertThat(parameters.containsKey("voice.reprompt.attempts")).isFalse();
    }

    @Test
    @DisplayName("what cannot be repaired goes back to the model once")
    void unrepairableViolationsRepromptWithinBudget() {
        var parameters = new InvocationParameters();
        var result = guardrail.validate(requestFor("Recomendo esse fundo.", parameters));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetry()).isTrue();
        assertThat(result.getReprompt()).isPresent();
        assertThat(result.getReprompt().orElseThrow()).contains("recomendo");
        assertThat(parameters.<Integer>get("voice.reprompt.attempts")).isEqualTo(1);
    }

    @Test
    @DisplayName("past the reprompt budget the answer is delivered, never withheld")
    void theBudgetEndsInDeliveryRatherThanAnException() {
        var parameters = new InvocationParameters();
        var request = requestFor("Recomendo esse fundo.", parameters);

        assertThat(guardrail.validate(request).isRetry()).isTrue();

        // The second pass has no budget left. LangChain4j's executor throws the
        // moment a guardrail keeps failing, so anything other than success here is a
        // 500 for a word choice.
        var second = guardrail.validate(request);
        assertThat(second.isSuccess()).isTrue();
        assertThat(second.isFatal()).isFalse();
    }

    @Test
    @DisplayName("with no invocation carrier the loop cannot be bounded, so it does not start")
    void withoutInvocationParametersItDeliversRatherThanLooping() {
        var request = OutputGuardrailRequest.builder()
                .responseFromLLM(ChatResponse.builder().aiMessage(AiMessage.from("Recomendo esse fundo.")).build())
                .chatExecutor(noExecutor())
                .requestParams(GuardrailRequestParams.builder()
                        .userMessageTemplate("")
                        .variables(Map.of())
                        .build())
                .build();

        assertThat(guardrail.validate(request).isSuccess()).isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    private OutputGuardrailRequest requestFor(String answer, InvocationParameters parameters) {
        return OutputGuardrailRequest.builder()
                .responseFromLLM(ChatResponse.builder().aiMessage(AiMessage.from(answer)).build())
                .chatExecutor(noExecutor())
                .requestParams(GuardrailRequestParams.builder()
                        .userMessageTemplate("")
                        .variables(Map.of())
                        .invocationContext(InvocationContext.builder()
                                .invocationId(UUID.randomUUID())
                                .interfaceName("ChatAssistant")
                                .methodName("chat")
                                .invocationParameters(parameters)
                                .timestampNow()
                                .build())
                        .build())
                .build();
    }

    /**
     * The guardrail must never reach the model on its own; a executor that throws
     * makes an accidental call a failing test rather than a silent second request.
     */
    private static ChatExecutor noExecutor() {
        return new ChatExecutor() {
            @Override
            public ChatResponse execute() {
                throw new AssertionError("The voice guardrail called the model directly");
            }

            @Override
            public ChatResponse execute(List<ChatMessage> chatMessages) {
                throw new AssertionError("The voice guardrail called the model directly");
            }
        };
    }

    private static VoiceProperties properties(int maxReprompts) {
        return new VoiceProperties() {
            @Override
            public String document() {
                return "voice/VOICE.md";
            }

            @Override
            public List<String> allowedEmoji() {
                return ALLOWED;
            }

            @Override
            public int maxBulletItems() {
                return 5;
            }

            @Override
            public int maxReprompts() {
                return maxReprompts;
            }
        };
    }
}
