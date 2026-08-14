package io.github.rodrigorjsf.agenticchat.triage;

import jakarta.inject.Singleton;

import java.util.Locale;
import java.util.Map;

/**
 * The words a user sees when a request is declined.
 *
 * <p>Written in Java, not by the model. Three reasons, in order:
 *
 * <ol>
 *   <li><b>Latency and cost.</b> Asking the judge to write the sentence measured
 *       roughly 40 extra output tokens per call, and output tokens are what drive
 *       judge latency — an 8-field schema measured a 1.463 s median against 1.073 s
 *       for 6 fields.</li>
 *   <li><b>Voice.</b> A refusal is the worst moment to improvise. These sentences
 *       are reviewed once, in a pull request, and then behave the same way every
 *       time.</li>
 *   <li><b>Testability.</b> Tone is asserted here rather than judged after the
 *       fact.</li>
 * </ol>
 *
 * <h2>The tone rules these follow</h2>
 *
 * Say plainly that it is outside what the assistant helps with. Name one or two
 * things it <em>can</em> do — a refusal that offers nothing reads as a wall.
 * Apologise at most once. Do not explain a policy, do not lecture, do not ask the
 * user to rephrase something that will be refused again.
 *
 * <p>Selection is total: every {@link TriageVerdict.Intent} has an entry, and an
 * unknown language falls back to Portuguese, which is this assistant's primary
 * audience.
 */
@Singleton
public class RefusalTemplates {

    private static final String PT = "pt";
    private static final String EN = "en";

    private static final Map<TriageVerdict.Intent, Map<String, String>> TEMPLATES = Map.ofEntries(
            Map.entry(TriageVerdict.Intent.EMPTY, Map.of(
                    PT, "Não recebi nenhuma mensagem. Pode escrever o que você precisa?",
                    EN, "I didn't get a message. What would you like to know?")),

            Map.entry(TriageVerdict.Intent.TOO_LONG, Map.of(
                    PT, "Essa mensagem é longa demais para eu processar. Pode resumir o que você precisa?",
                    EN, "That message is too long for me to process. Could you summarise what you need?")),

            Map.entry(TriageVerdict.Intent.PROFESSIONAL_ADVICE, Map.of(
                    PT, "Isso precisa de um profissional, não de mim. Posso ajudar com dados públicos "
                            + "brasileiros, clima e informações gerais.",
                    EN, "That needs a professional rather than me. I can help with Brazilian public data, "
                            + "weather and general information.")),

            Map.entry(TriageVerdict.Intent.CODE_REQUEST, Map.of(
                    PT, "Não escrevo código. Posso consultar CEP, CNPJ, feriados, clima e outros dados "
                            + "públicos, se ajudar.",
                    EN, "I don't write code. I can look up postal codes, companies, holidays, weather and "
                            + "other public data, if that helps.")),

            Map.entry(TriageVerdict.Intent.HARMFUL_REQUEST, Map.of(
                    PT, "Não posso ajudar com isso. Se precisar de dados públicos, clima ou informações "
                            + "gerais, é só pedir.",
                    EN, "I can't help with that. If you need public data, weather or general information, "
                            + "just ask.")),

            Map.entry(TriageVerdict.Intent.PROMPT_INJECTION, Map.of(
                    PT, "Não consigo ajudar com essa mensagem. Posso consultar dados públicos brasileiros, "
                            + "clima e informações gerais.",
                    EN, "I can't help with that message. I can look up Brazilian public data, weather and "
                            + "general information.")),

            Map.entry(TriageVerdict.Intent.OFF_TOPIC, Map.of(
                    PT, "Isso está fora do que eu faço. Posso ajudar com CEP, empresas, feriados, clima e "
                            + "outros dados públicos.",
                    EN, "That's outside what I do. I can help with postal codes, companies, holidays, "
                            + "weather and other public data.")),

            Map.entry(TriageVerdict.Intent.UNKNOWN, Map.of(
                    PT, "Não entendi bem o pedido. Posso ajudar com dados públicos brasileiros, clima e "
                            + "informações gerais.",
                    EN, "I didn't quite follow that. I can help with Brazilian public data, weather and "
                            + "general information.")));

    /**
     * These intents are never refused, so a template for them would be dead code —
     * but selection must still be total, so they resolve to the generic message if
     * the routing ever changes.
     */
    private static final Map<String, String> FALLBACK = Map.of(
            PT, "Isso está fora do que eu faço. Posso ajudar com dados públicos brasileiros, clima e "
                    + "informações gerais.",
            EN, "That's outside what I do. I can help with Brazilian public data, weather and general "
                    + "information.");

    public String refusalFor(TriageVerdict verdict) {
        return refusalFor(verdict.intent(), verdict.language());
    }

    public String refusalFor(TriageVerdict.Intent intent, String languageTag) {
        var byLanguage = TEMPLATES.getOrDefault(intent, FALLBACK);
        return byLanguage.getOrDefault(primaryLanguage(languageTag), byLanguage.get(PT));
    }

    /** {@code pt-BR} and {@code pt} both select Portuguese; anything unknown does too. */
    private static String primaryLanguage(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            return PT;
        }
        String primary = languageTag.toLowerCase(Locale.ROOT).split("-")[0];
        return EN.equals(primary) ? EN : PT;
    }
}
