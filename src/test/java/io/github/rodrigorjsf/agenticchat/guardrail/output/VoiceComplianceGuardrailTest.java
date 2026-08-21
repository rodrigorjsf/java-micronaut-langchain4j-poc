package io.github.rodrigorjsf.agenticchat.guardrail.output;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.rodrigorjsf.agenticchat.voice.VoiceProfile;
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

    private static final String DE_ESCALATION = VoiceComplianceGuardrail.DE_ESCALATION;

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
    @DisplayName("a term the document no longer carries is not enforced on the model")
    void theBrandRuleLeftWithTheDocumentThatStatedIt() {
        // "Banco Inter" -> "Inter" belongs to voice/VOICE_EXAMPLE.md. Left behind here
        // it would rewrite the name of a real company returned by a CNPJ lookup.
        assertThat(guardrail.repair("O Banco Inter S.A. está ativo desde 1994."))
                .isEqualTo("O Banco Inter S.A. está ativo desde 1994.");
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
            "Peço que tu juntes os comprovantes."})
    @DisplayName("a compound, a hostname and correct Portuguese are not violations")
    void identifiersAndValidPortugueseDoNotFire(String answer) {
        assertThat(guardrail.violations(answer)).isEmpty();
    }

    @Test
    @DisplayName("a snake_case identifier carrying a listed term costs one reprompt, deliberately")
    void theUnderscoreTradeIsDeliberate() {
        // Treating "_" as an identifier character protected total_uai_mensal and cost
        // every rule in the map: _Recomendo_ and __Recomendo__ went unmatched, and
        // underscore emphasis is something a model emits by habit. The trade is
        // asymmetric — this costs a reprompt, the alternative shipped investment
        // advice — so it is asserted rather than left to be rediscovered.
        assertThat(guardrail.violations("O campo total_uai_mensal traz o acumulado."))
                .anyMatch(rule -> rule.contains("regionalism"));
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

    // ------------------------------------------------- evasion, not damage

    @ParameterizedTest
    @ValueSource(strings = {
            "_Recomendo_ esse fundo.",
            "__Recomendo__ esse fundo.",
            "**Recomendo** esse fundo.",
            "Recomendo/sugiro esse fundo.",
            "Seria melhor investir/aplicar no CDB.",
            "_veja mais_ no aplicativo."})
    @DisplayName("markdown emphasis and a slash do not hide a forbidden term")
    void emphasisDoesNotDefeatTheRule(String answer) {
        assertThat(guardrail.violations(answer)).isNotEmpty();
    }

    @Test
    @DisplayName("a loose list is still a list")
    void blankLinesBetweenItemsDoNotDisableTheCap() {
        // The document mandates a blank line between lists AND between content, so
        // this is the shape it pushes the model toward. Reading one blank line as the
        // end of the list let ten items past both bullet rules.
        String answer = "Documentos:\n\n- a\n\n- b\n\n- c\n\n- d\n\n- e\n\n- f";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("at most 5 bullet points"))
                .anyMatch(rule -> rule.contains("bullets are written"));
    }

    @Test
    @DisplayName("two blank lines, or a paragraph, do end it")
    void separatedListsAreStillSeparate() {
        String answer = "Física:\n\n• RG\n• CPF\n• CNH\n\nJurídica:\n\n• CNPJ\n• Contrato\n• Procuração";

        assertThat(guardrail.violations(answer)).isEmpty();
    }

    @Test
    @DisplayName("an item that wraps onto the next line is still one item")
    void aWrappedItemDoesNotSplitTheRun() {
        String answer = "• Selic acumulada\n  nos últimos 90 dias\n• CDI\n• IPCA\n• IGP-M\n• INPC\n• TR";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("at most 5 bullet points"));
    }

    // ----------------------------------------- indented code, and fixed points

    @Test
    @DisplayName("a four-space code block is code, not a list and not prose")
    void indentedCodeIsNotProse() {
        String answer = "Exemplo de YAML:\n\n    itens:\n    - um\n    - dois\n    - tres\n\nPronto.";

        assertThat(guardrail.violations(answer)).isEmpty();
        assertThat(guardrail.repair(answer)).isEqualTo(answer);
    }

    @Test
    @DisplayName("a replacement does not reach a four-space code block either")
    void replacementsDoNotReachIndentedCode() {
        String answer = "Exemplo:\n\n    var tod@s = lista;\n\nPronto.";

        assertThat(guardrail.violations(answer)).noneMatch(rule -> rule.startsWith("wording:"));
        assertThat(guardrail.repair(answer)).contains("var tod@s = lista;");
    }

    @Test
    @DisplayName("four spaces under a bullet is that item continuing, not code")
    void deepIndentUnderABulletIsStillAList() {
        String answer = "• Cartão\n    • Crédito\n    • Débito\n• Conta\n• Pix\n• Boleto\n• TED\n• DOC";

        assertThat(guardrail.violations(answer))
                .anyMatch(rule -> rule.contains("at most 5 bullet points"));
    }

    @Test
    @DisplayName("repairing twice changes nothing more than repairing once")
    void repairIsAFixedPoint() {
        // Removing "😀 " from the head of a line turns something the glyph fixer had
        // already walked past into a bullet. One pass shipped a list with mixed
        // markers and asked the model to fix a glyph the guardrail could fix.
        String answer = "Resumo:\n\n😀 - item um\n- item dois\n- item tres";
        String once = guardrail.repair(answer);

        assertThat(guardrail.repair(once)).isEqualTo(once);
        assertThat(once).doesNotContain("- item").contains("• item");
        assertThat(guardrail.violations(once)).isEmpty();
    }

    @Test
    @DisplayName("a table is not reflowed, and the emoji does not land inside a row")
    void tablesSurviveIntact() {
        String answer = "Compare ⏰:\n\n| Canal  | Prazo    |\n|--------|----------|\n| Pix    | imediato |";

        assertThat(guardrail.repair(answer))
                .contains("| Canal  | Prazo    |")
                .contains("| Pix    | imediato |")
                .doesNotContain("imediato | ⏰");
    }

    @Test
    @DisplayName("the guardrail enforces no term the document does not state")
    void theTermCatalogueRunsInBothDirections() {
        // How "Banco Inter" -> "Inter" survived in the guardrail after the brand left
        // the document: nothing checked. Left there it would have rewritten the name
        // of a real company returned by a CNPJ lookup, enforcing a rule the model was
        // never given.
        assertThat(VoiceComplianceGuardrail.enforcedTerms())
                .allSatisfy(term -> assertThat(new VoiceProfile(properties(1)).document())
                        .as("\"%s\" is enforced on the answer, so the document must state it", term)
                        .containsIgnoringCase(term));
    }

    // ------------------------------------------------------ throat-clearing

    @ParameterizedTest
    @ValueSource(strings = {
            "Claro! O CEP da Avenida Paulista 1578 é 01310-200.",
            "Com certeza! A SELIC está em 15% ao ano.",
            "Certamente, o feriado cai numa segunda.",
            "Ótima pergunta! O IPCA acumulado é 4,2%.",
            "Sure! The postal code is 01310-200.",
            "Of course, the rate is 15%."})
    @DisplayName("an acknowledgement opener is reported and stripped")
    void throatClearingIsRemoved(String answer) {
        assertThat(guardrail.violations(answer)).anyMatch(rule -> rule.startsWith("opening:"));

        String repaired = guardrail.repair(answer);
        assertThat(guardrail.violations(repaired)).isEmpty();
        assertThat(repaired).matches("^[A-ZÁÉÍÓÚÂÊÔÃÕÇ].*");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Claro que sim, o feriado é nacional.",
            "Certamente esse dado existe, mas a fonte está fora do ar.",
            "O céu está claro, com poucas nuvens.",
            "Perfeito para quem viaja: o feriado cai numa sexta."})
    @DisplayName("the same word without the punctuation that makes it an opener is left alone")
    void ordinaryUsesOfTheSameWordsAreNotOpeners(String answer) {
        assertThat(guardrail.violations(answer)).isEmpty();
        assertThat(guardrail.repair(answer)).isEqualTo(answer);
    }

    @Test
    @DisplayName("stripping the opener leaves the rest of the answer alone")
    void theAnswerAfterTheOpenerIsUntouched() {
        assertThat(guardrail.repair("Claro! O CEP é 01310-200, na Bela Vista."))
                .isEqualTo("O CEP é 01310-200, na Bela Vista.");
    }

    // ------------------------------------ the sentence the document mandates

    @Test
    @DisplayName("an answer to an offensive turn must carry the mandated sentence")
    void theMandatedReplyIsRequiredWhenTheTurnCarriesOffence() {
        // The failure this rule exists for is the ordinary one: verbatim reproduction
        // of a fixed sentence under contextual pressure. The model writes something
        // that means the same thing and the contract is a paraphrase.
        var result = guardrail.validate(requestFor(
                "Entendo sua frustração, mas prefiro focar em como posso te ajudar hoje.",
                offendedTurn()));

        assertThat(result.isSuccess())
                .as("nothing checked for a required phrase; violations() held forbidden-term "
                        + "lists only")
                .isFalse();
        assertThat(result.isRetry())
                .as("the answer is not withheld — it goes back to the model once, inside the "
                        + "same reprompt budget every other rule here shares")
                .isTrue();
    }

    @Test
    @DisplayName("a model that will not say the sentence still gets its answer delivered")
    void theMandatedReplyStopsBeingAskedForOnceTheBudgetIsSpent() {
        // The rule this class exists to obey, applied to its own newest rule:
        // OutputGuardrailExecutor throws the moment ITS budget runs out, so a rule that
        // keeps failing turns a word choice into a 5xx. One InvocationParameters, reused
        // the way the executor reuses it across the retries of one turn.
        var parameters = offendedTurn();
        var request = requestFor(
                "Entendo sua frustração, mas prefiro focar em como posso te ajudar hoje.", parameters);

        assertThat(guardrail.validate(request).isRetry())
                .as("first attempt: one reprompt, naming the exact sentence")
                .isTrue();
        assertThat(guardrail.validate(request).isSuccess())
                .as("budget spent: the answer is delivered and the residue counted, never withheld")
                .isTrue();
    }

    @Test
    @DisplayName("the mandated sentence, present word for word, passes untouched")
    void theMandatedReplyIsAcceptedVerbatim() {
        var result = guardrail.validate(requestFor(DE_ESCALATION, offendedTurn()));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("frustration is not offence, and never asks for the de-escalation")
    void frustrationAloneNeverAsksForTheDeEscalation() {
        // VOICE.md, in the same section that mandates the sentence: "A message
        // expressing frustration with an answer is not an offence. Take it as a signal
        // that the answer missed, and ask what was wrong with it." A FRUSTRATION
        // verdict raises no offence flag, so this answer must pass. Keying the rule off
        // FRUSTRATION instead would answer a complaint about a wrong CEP with a
        // de-escalation script, which is a worse bug than the one being fixed.
        var result = guardrail.validate(requestFor(
                "O CEP que passei não bateu com o endereço. O que exatamente estava errado nele?",
                new InvocationParameters()));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("an ordinary turn is never asked for it either")
    void theMandatedReplyIsNotRequiredOfAnOrdinaryTurn() {
        assertThat(guardrail.validate(requestFor(
                "O CEP da Avenida Paulista 1578 é 01310-200.", new InvocationParameters()))
                .isSuccess()).isTrue();
    }

    @Test
    @DisplayName("one earlier de-escalation in the window is not two")
    void theDeEscalationIsStillRequiredAfterASingleReply() {
        var memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(dev.langchain4j.data.message.UserMessage.from("seu lixo"));
        memory.add(AiMessage.from(DE_ESCALATION));
        memory.add(dev.langchain4j.data.message.UserMessage.from("seu lixo de novo"));

        var result = guardrail.validate(requestFor(
                "Vamos tentar de novo: o que você precisa consultar?", offendedTurn(), memory));

        assertThat(result.isRetry()).isTrue();
    }

    @Test
    @DisplayName("after two replies carrying it, the document says stop answering the tone")
    void theDeEscalationStopsBeingRequiredAfterTwoReplies() {
        // "Where you can see that the tone has not improved after two of your replies,
        // stop answering the tone: answer the factual part of the message if there is
        // one." A rule that kept demanding the sentence would enforce one clause of
        // this section by breaking the next one. The count comes from the memory
        // window — the same record the document tells the model to read, and the same
        // one it warns "does not reach back forever".
        var memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(dev.langchain4j.data.message.UserMessage.from("seu lixo"));
        memory.add(AiMessage.from(DE_ESCALATION));
        memory.add(dev.langchain4j.data.message.UserMessage.from("seu lixo de novo"));
        memory.add(AiMessage.from(DE_ESCALATION));
        memory.add(dev.langchain4j.data.message.UserMessage.from("seu lixo, e qual o cep da paulista"));

        var result = guardrail.validate(requestFor(
                "O CEP da Avenida Paulista 1578 é 01310-200.", offendedTurn(), memory));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("the mandated-sentence catalogue runs in both directions")
    void theMandatedSentenceCatalogueRunsInBothDirections() {
        // Negative control first, on the parser itself. Direction two below reads the
        // document through sentencesMandatedBy, so a marker list that quietly fails to
        // fire makes the whole check a rubber stamp that still reports green. BOTH
        // markers are asserted, because only one of them is live at a time: the shipped
        // VOICE.md:140 says "reply exactly:", inherited from the brand contract at
        // voice/VOICE_EXAMPLE.md, and a parser that reads only the live wording cannot
        // see a future clause phrased the other way — which is issue #7 again. Each
        // sample is in the markdown shape VOICE.md:140-141 uses: marker line, then an
        // indented sub-bullet carrying the sentence.
        assertThat(sentencesMandatedBy("- reply exactly:\n  - Uma frase qualquer."))
                .as("the phrasing live in the shipped document must not evade the parser")
                .containsExactly("Uma frase qualquer.");
        assertThat(sentencesMandatedBy("- carry this sentence, word for word:\n  - Outra frase."))
                .as("a clause phrased \"word for word:\" must not evade the parser either")
                .containsExactly("Outra frase.");

        String document = new VoiceProfile(properties(1)).document();

        // Direction one, the same rule as theTermCatalogueRunsInBothDirections: a
        // sentence this class demands must be a sentence the model was given.
        assertThat(VoiceComplianceGuardrail.mandatedSentences())
                .allSatisfy(sentence -> assertThat(document)
                        .as("\"%s\" is required of the answer, so the document must state it", sentence)
                        .contains(sentence));

        // Direction two, and it is the whole of issue #7: a sentence the document
        // mandates word for word and nothing requires is a contract clause no path
        // emits. One-directional, this check would pass forever while the two halves
        // drifted apart.
        assertThat(sentencesMandatedBy(document))
                .as("every sentence VOICE.md mandates word for word is required by this class")
                .isEqualTo(VoiceComplianceGuardrail.mandatedSentences());
    }

    /**
     * Every sentence the document mandates verbatim, read out of the document itself:
     * the line after each one ending in one of the {@link #VERBATIM_MARKERS}.
     *
     * <p>Two markers for one document, and that is the point of the list. Only one is
     * live at a time — the shipped {@code VOICE.md:140} says "reply exactly:" — so
     * keying on that phrase alone would still catch a rewording of that line, because
     * direction two goes empty, but would let a NEW clause phrased "word for word:"
     * pass unseen. A parser that reads only the wording already enforced cannot detect
     * the clause nobody enforced yet, which is issue #7 again.
     */
    private static java.util.Set<String> sentencesMandatedBy(String document) {
        var mandated = new java.util.LinkedHashSet<String>();
        String[] lines = document.split("\\R");
        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i].stripTrailing();
            if (VERBATIM_MARKERS.stream().anyMatch(line::endsWith)) {
                mandated.add(lines[i + 1].strip().replaceFirst("^[-*•]\\s*", ""));
            }
        }
        return mandated;
    }

    /**
     * The ways this document introduces a sentence it wants reproduced word for word.
     */
    private static final java.util.List<String> VERBATIM_MARKERS =
            java.util.List.of("word for word:", "reply exactly:");

    /**
     * What ChatTurnService puts in the invocation parameters when the triage verdict
     * carries the offence risk flag.
     */
    private static InvocationParameters offendedTurn() {
        var parameters = new InvocationParameters();
        parameters.put(VoiceComplianceGuardrail.OFFENCE_KEY, true);
        return parameters;
    }

    // ------------------------------------------------------------------ fixtures

    private OutputGuardrailRequest requestFor(String answer, InvocationParameters parameters) {
        return requestFor(answer, parameters, null);
    }

    /**
     * The memory is null on most of these, and deliberately: LangChain4j builds the
     * guardrail params with whatever memory the service has, and a guardrail that
     * needs one to decide would fail closed on a memory-less AI service.
     */
    private OutputGuardrailRequest requestFor(String answer, InvocationParameters parameters,
                                              ChatMemory memory) {
        return OutputGuardrailRequest.builder()
                .responseFromLLM(ChatResponse.builder().aiMessage(AiMessage.from(answer)).build())
                .chatExecutor(noExecutor())
                .requestParams(GuardrailRequestParams.builder()
                        .userMessageTemplate("")
                        .variables(Map.of())
                        .chatMemory(memory)
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
