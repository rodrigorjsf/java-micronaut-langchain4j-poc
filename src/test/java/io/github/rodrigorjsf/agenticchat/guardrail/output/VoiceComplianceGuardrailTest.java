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
    @DisplayName("two emoji mean the judgement was not exercised, so both go")
    void twoEmojiAreRepairedToNone() {
        // Keeping the prettier one would turn a countable violation into an
        // unmeasurable one: the document also says never in a serious message and
        // "if you are unsure, do not use it", and neither is checkable here.
        String answer = "Bom dia 😊 o CEP é 01310-100. 💡";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("ONLY 1 emoji"));
        assertThat(guardrail.repair(answer)).isEqualTo("Bom dia o CEP é 01310-100.");
        assertThat(guardrail.violations(guardrail.repair(answer))).isEmpty();
    }

    @Test
    @DisplayName("a problem report does not come back wearing a smiley")
    void aSeriousAnswerIsNotRepairedIntoOneEmoji() {
        String answer = "O sistema está fora do ar ⚠️ e ainda não há previsão 😊";

        assertThat(guardrail.repair(answer))
                .isEqualTo("O sistema está fora do ar e ainda não há previsão");
    }

    @Test
    void anEmojiOutsideTheAllowListIsRemoved() {
        String answer = "Consegui encontrar o dado. 🚀";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("is not on the allowed list"));
        assertThat(guardrail.repair(answer)).isEqualTo("Consegui encontrar o dado.");
    }

    @Test
    @DisplayName("an emoji in the middle is moved to the end rather than deleted")
    void misplacedEmojiIsMovedToTheEnd() {
        String answer = "O prazo ⏰ é de dois dias úteis.";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("at the end of the message"));
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
                .anyMatch(rule -> rule.contains("ableist"));
        assertThat(guardrail.violations("Tudo na palma da mão."))
                .anyMatch(rule -> rule.contains("ableist"));
    }

    @Test
    void regionalismsAreMatchedAsWholeWordsOnly() {
        assertThat(guardrail.violations("Uai, deu certo.")).anyMatch(rule -> rule.contains("regionalism"));
        // "uai" inside another word is not a regionalism, and neither is a company
        // name that happens to contain it.
        assertThat(guardrail.violations("A empresa Guaiba Ltda está ativa.")).isEmpty();
    }

    // -------------------------------------------------------------------- lists

    @Test
    void aSixthBulletIsReported() {
        String answer = "Documentos aceitos:\n• RG\n• CPF\n• CNH\n• Passaporte\n• Título\n• Carteira";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("at most 5 bullet points"));
    }

    @Test
    void fiveBulletsAreFine() {
        String answer = "Documentos aceitos:\n• RG\n• CPF\n• CNH\n• Passaporte\n• Título";

        assertThat(guardrail.violations(answer)).isEmpty();
    }

    @Test
    @DisplayName("a markdown dash is not the glyph the document names")
    void markdownBulletsAreRewrittenToTheMandatedGlyph() {
        String answer = "Documentos aceitos:\n- RG\n- CPF";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("bullets are written"));
        assertThat(guardrail.repair(answer)).isEqualTo("Documentos aceitos:\n• RG\n• CPF");
    }

    @Test
    @DisplayName("indentation survives, so a nested list stays nested")
    void nestedBulletsKeepTheirIndentation() {
        assertThat(guardrail.repair("- RG\n  * segunda via")).isEqualTo("• RG\n  • segunda via");
    }

    @Test
    @DisplayName("a dash inside a fenced code block is an argument, not a bullet")
    void fencedCodeIsLeftAlone() {
        // Two items below the fence, because one dash on its own is a footnote and
        // is deliberately left alone.
        String answer = "Rode:\n\n```bash\ncurl -s https://exemplo\n- nao e bullet\n```\n\n- item\n- outro";

        assertThat(guardrail.repair(answer))
                .contains("curl -s https://exemplo")
                .contains("- nao e bullet")
                .endsWith("• item\n• outro");
    }

    @Test
    @DisplayName("removing an emoji does not reindent fenced code")
    void repairLeavesCodeIndentationAlone() {
        // Collapsing runs of spaces is part of removing an emoji, and run over the
        // whole answer it silently reindents Python, YAML and Makefiles.
        String answer = "Exemplo 😊 abaixo:\n\n```python\ndef f():\n    if x:\n        return 1\n```";

        assertThat(guardrail.repair(answer))
                .contains("\n    if x:\n        return 1")
                .startsWith("Exemplo abaixo:");
    }

    @Test
    @DisplayName("an emoji inside a code sample is part of the sample")
    void emojiInsideAFenceIsNotCounted() {
        String answer = "Assim:\n\n```python\nprint(\"😊 💡 🚀\")\n```";

        assertThat(guardrail.violations(answer)).isEmpty();
        assertThat(guardrail.repair(answer)).isEqualTo(answer);
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
        assertThat(result.successfulText()).isEqualTo("Todos podem acessar.");
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

    // ------------------------------------------- symbols that are not emoji

    @ParameterizedTest
    @ValueSource(strings = {
            "Pagamento ✓ confirmado. 😊",
            "Status ✗ pendente. 😊",
            "Avaliação ★★★★★ do serviço. 😊",
            "Passo ❶ do processo. 😊",
            "Atalho ⌘K para buscar. 😊",
            "Produto™ registrado. 😊",
            "Início → Pix → Enviar. 😊",
            "Temperatura de 25° hoje. 😊"})
    @DisplayName("a typographic symbol is not the second emoji")
    void ordinarysymbolsAreNotCountedAsEmoji(String answer) {
        // Ranges picked by eye put ✓, ★, ❶, ⌘ and ™ in the same class as 😊, and the
        // repair then deleted the one emoji the brand actually allows.
        assertThat(guardrail.violations(answer)).isEmpty();
        assertThat(guardrail.repair(answer)).isEqualTo(answer);
    }

    @Test
    @DisplayName("a comparison table keeps its check marks")
    void checkMarksSurviveARepair() {
        String answer = "Comparativo 🚀:\n\n| Pix | ✓ | ✗ |\n| Cartão | ✓ | ✓ |";

        // 🚀 is off the allow-list and goes; the table is not touched.
        assertThat(guardrail.repair(answer)).isEqualTo("Comparativo:\n\n| Pix | ✓ | ✗ |\n| Cartão | ✓ | ✓ |");
    }

    @Test
    @DisplayName("a text-presentation symbol becomes an emoji when it carries the selector")
    void theVariationSelectorPromotesASymbol() {
        assertThat(guardrail.violations("Concluído ✔️ e enviado 😊"))
                .anyMatch(rule -> rule.contains("ONLY 1 emoji"));
    }

    // ------------------------------------------------------- code that is not prose

    @Test
    @DisplayName("a four-space indented block keeps its indentation")
    void indentedCodeIsNotReflowed() {
        String answer = "Exemplo 🚀:\n\n    chave = \"11999998888\"\n        formato = \"telefone\"";

        assertThat(guardrail.repair(answer))
                .contains("\n    chave = \"11999998888\"\n        formato = \"telefone\"");
    }

    @Test
    @DisplayName("the kept emoji is never glued onto a closing fence")
    void anAnswerEndingInCodeLosesTheEmojiRatherThanTheFence() {
        String answer = "O prazo ⏰ para o estorno:\n\n```\nD+2 uteis\n```";

        assertThat(guardrail.repair(answer)).isEqualTo("O prazo para o estorno:\n\n```\nD+2 uteis\n```");
    }

    @Test
    @DisplayName("an unpaired fence does not switch the rules off for the rest of the answer")
    void anUnbalancedFenceIsTreatedAsProse() {
        String answer = "Veja:\n```\n• RG\n• CPF\n• CNH\n• Passaporte\n• Título\n• Carteira";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("at most 5 bullet points"));
    }

    // -------------------------------------------------- lines that are not bullets

    @ParameterizedTest
    @ValueSource(strings = {
            "* * *",
            "- - -",
            "___",
            "* Valores sujeitos a alteração pelo Bacen.",
            "- Não tenho o comprovante.",
            "- 250,00 reais de tarifa"})
    @DisplayName("a rule, a footnote and a line of dialogue are not lists")
    void aSingleDashLineIsNotABullet(String answer) {
        assertThat(guardrail.violations(answer)).isEmpty();
        assertThat(guardrail.repair(answer)).isEqualTo(answer);
    }

    @Test
    @DisplayName("a footnote below a full list is not its sixth item")
    void aFootnoteDoesNotOverflowTheListAboveIt() {
        String answer = "Documentos:\n• RG\n• CPF\n• CNH\n• Passaporte\n• Título\n\n* Trazer os originais.";

        assertThat(guardrail.violations(answer)).isEmpty();
    }

    @Test
    @DisplayName("sub-items are not items")
    void nestedBulletsAreNotCountedAsTopLevelItems() {
        String answer = "• Cartão\n  • Crédito\n  • Débito\n• Conta\n  • Corrente\n  • Poupança";

        assertThat(guardrail.violations(answer)).isEmpty();
    }

    @Test
    @DisplayName("the blank line the document mandates ends the list")
    void aBlankLineResetsTheRun() {
        String answer = "Pessoa física:\n\n• RG\n• CPF\n• CNH\n\nPessoa jurídica:\n\n• CNPJ\n• Contrato\n• Procuração";

        assertThat(guardrail.violations(answer)).isEmpty();
    }

    // ----------------------------------------------------- word boundaries

    @ParameterizedTest
    @ValueSource(strings = {
            "Acesse o portal Uai-Minas para consultar.",
            "O endereço é uai.com.br para essa consulta.",
            "O campo total_uai_mensal traz o acumulado.",
            "Peço que tu juntes os comprovantes."})
    @DisplayName("an identifier, a hostname and correct Portuguese are not violations")
    void identifiersAndValidPortugueseDoNotFire(String answer) {
        assertThat(guardrail.violations(answer)).isEmpty();
    }

    @Test
    @DisplayName("a full stop still ends a sentence")
    void aTermAtTheEndOfASentenceStillMatches() {
        assertThat(guardrail.violations("Não recomendo.")).anyMatch(rule -> rule.contains("recomendo"));
    }

    @Test
    @DisplayName("an all-caps heading does not come back mixed case")
    void capitalisationOfTheWholeMatchIsKept() {
        assertThat(guardrail.repair("TODES PODEM ACESSAR.")).isEqualTo("TODOS PODEM ACESSAR.");
    }

    // ------------------------------------------------------------- the budget

    @Test
    @DisplayName("a reprompt budget the executor cannot honour is clamped, not obeyed")
    void anOversizedRepromptBudgetIsClamped() {
        // Measured: with the executor's own maxRetries at 2, asking for 2 reprompts
        // makes the last failure throw and the answer is lost. A cosmetic rule must
        // never be one config character away from a 5xx.
        var generous = new VoiceComplianceGuardrail(properties(5), new SimpleMeterRegistry());
        var parameters = new InvocationParameters();
        var request = requestFor("Recomendo esse fundo.", parameters);

        assertThat(generous.validate(request).isRetry()).isTrue();
        assertThat(generous.validate(request).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("an enclitic pronoun does not hide the term it attaches to")
    void encliticFormsStillMatch() {
        // Closing the uai-minas hole opened a worse one: a hyphen was made an
        // identifier character, and "Recomendo-lhe esse fundo" is both standard
        // formal Portuguese and exactly the sentence the clause exists to catch.
        assertThat(guardrail.violations("Recomendo-lhe esse fundo.")).anyMatch(r -> r.contains("recomendo"));
        assertThat(guardrail.violations("Recomendo-te outro produto.")).anyMatch(r -> r.contains("recomendo"));
        assertThat(guardrail.violations("Acesse o portal Uai-Minas.")).isEmpty();
    }

    @Test
    @DisplayName("a code sample is not rewritten for a violation nobody reported")
    void replacementsDoNotReachFencedCode() {
        // violations() reads prose; repair() used to read the whole answer. The
        // asymmetry rewrote a code sample for a violation that was never reported
        // and shipped it as a success.
        String answer = "Veja 🚀:\n\n```java\nvar tod@s = lista;\n```";

        assertThat(guardrail.violations(answer)).noneMatch(rule -> rule.startsWith("wording:"));
        assertThat(guardrail.repair(answer)).contains("var tod@s = lista;");
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
