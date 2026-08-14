package io.github.rodrigorjsf.agenticchat.rag;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import jakarta.inject.Singleton;

import java.util.Collection;
import java.util.List;

/**
 * Decides whether a turn should retrieve at all.
 *
 * <p><b>This component exists because a similarity threshold provably cannot do
 * the job.</b> Measured on this corpus with the quantized MiniLM model, relevance
 * scores for the top hit:
 *
 * <pre>
 *   0.8475  como-ler-a-previsao.md          "a previsao esta em qual fuso horario?"
 *   0.7976  sobre-o-assistente.md           "o que voce consegue fazer?"
 *   0.7938  como-ler-a-previsao.md          "o que quer dizer o codigo 95 na previsao?"
 *   0.7937  dados-publicos-brasileiros.md   "o que significa o codigo IBGE de um municipio?"
 *   0.7469  sobre-o-assistente.md           "quais sao suas limitacoes?"
 *   0.7342  sobre-o-assistente.md           "quem ganhou a copa do mundo de 1994"      &lt;-- irrelevant
 *   0.7299  dados-publicos-brasileiros.md   "por que o CEP veio sem rua e sem bairro?"
 *   0.7058  dados-publicos-brasileiros.md   "qual e a receita de bolo de cenoura"      &lt;-- irrelevant
 *   0.6826  sobre-o-assistente.md           "escreva um script em python"              &lt;-- irrelevant
 * </pre>
 * <p>
 * The distributions <em>overlap</em>: an irrelevant question outscores a relevant
 * one. No threshold separates them, so the earlier plan — "no router, just use
 * minScore" — was wrong, and the measurement is what said so.
 *
 * <p>The routing signal is the triage verdict's skill hint, which costs nothing:
 * it was produced by a model call that already happened. When the judge named a
 * skill, a tool will answer and retrieved prose would only compete with it. When
 * it named none, the turn is conversational or about the assistant itself, which
 * is exactly what the corpus covers.
 *
 * <p>{@code minScore} stays as a second gate inside the routed path, so a routed
 * turn with no good match still retrieves nothing rather than the nearest thing.
 */
@Singleton
public class SkillAwareQueryRouter implements QueryRouter {

    /**
     * Set by {@code ChatTurnService} through {@code InvocationParameters}.
     */
    public static final String SKILL_HINT = "triage.skillHint";

    private final ContentRetriever knowledge;

    public SkillAwareQueryRouter(ContentRetriever knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public Collection<ContentRetriever> route(Query query) {
        return skillHintOf(query).isEmpty() ? List.of(knowledge) : List.of();
    }

    private static String skillHintOf(Query query) {
        var metadata = query.metadata();
        if (metadata == null || metadata.invocationContext() == null) {
            return "";
        }
        var parameters = metadata.invocationContext().invocationParameters();
        if (parameters == null) {
            return "";
        }
        Object hint = parameters.get(SKILL_HINT);
        return hint == null ? "" : String.valueOf(hint);
    }
}
