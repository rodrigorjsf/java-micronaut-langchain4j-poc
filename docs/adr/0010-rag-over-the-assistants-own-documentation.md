# 0010 — Retrieve the assistant's own documentation, routed by skill hint

**Status:** Accepted · **[measured]**

## Context

Retrieval needs a corpus, and the obvious choice — facts about the world — is the
wrong one here. World facts come from tools, where they are current and
authoritative; a retrieval corpus of the same facts would go stale and compete
with the tools for the model's trust.

What tools cannot answer is everything *about* the assistant: what it can do, why
it said "not found", what a CEP actually is, how to read a WMO weather code.
Those questions are in scope by definition and currently get improvised answers.

## Decision

**The corpus is the assistant's own documentation**, three Markdown files under
`resources/knowledge/`, embedded at startup into an in-memory store with the
in-process quantized MiniLM ([0003](0003-no-local-model.md)).

No vector database. At this size the corpus is a few dozen segments and well
under a megabyte — a container and an operational surface bought for nothing. The
store is rebuilt on every boot, which also means it can never drift from the
files in the repository.

`storeRetrievedContentInChatMemory` is set to **false**. The default is true,
which writes every retrieved segment into persisted chat memory: it inflates each
DynamoDB item and replays the same prose into every later prompt of the
conversation. Retrieval is cheap enough to redo per turn; storing it is not.

## The part that changed after measuring

The first design had no query router. The reasoning was that the embedding model
runs in-process at ~14 ms and costs nothing, so routing would save 14 ms and add
a component that can be wrong — a `minScore` threshold would do the same job with
no moving parts.

Then the scores were measured. Top-hit relevance on this corpus:

```
0.8475  como-ler-a-previsao.md          "a previsao esta em qual fuso horario?"
0.7976  sobre-o-assistente.md           "o que voce consegue fazer?"
0.7938  como-ler-a-previsao.md          "o que quer dizer o codigo 95 na previsao?"
0.7937  dados-publicos-brasileiros.md   "o que significa o codigo IBGE de um municipio?"
0.7469  sobre-o-assistente.md           "quais sao suas limitacoes?"
0.7342  sobre-o-assistente.md           "quem ganhou a copa do mundo de 1994"      <-- irrelevant
0.7299  dados-publicos-brasileiros.md   "por que o CEP veio sem rua e sem bairro?"
0.7058  dados-publicos-brasileiros.md   "qual e a receita de bolo de cenoura"      <-- irrelevant
0.6826  sobre-o-assistente.md           "escreva um script em python"              <-- irrelevant
```

**The distributions overlap.** An irrelevant question (0.7342) outscores a
relevant one (0.7299). No choice of `minScore` separates them, so "just use the
threshold" was not a simplification — it was a design that could not work. The
measurement is what said so, and the overlap is now pinned by a test so that a
future change of model or corpus reveals whether it still holds.

**`SkillAwareQueryRouter`** therefore decides per turn, using a signal that costs
nothing because it already exists: the triage verdict's skill hint. If the judge
named a skill, a tool will answer and retrieved prose would only compete with it.
If it named none, the turn is conversational or about the assistant itself —
exactly what the corpus covers. The hint travels on LangChain4j's
`InvocationParameters` rather than in the prompt text, so the model does not read
it twice.

`minScore` stays at **0.72** as a second gate inside the routed path, so a routed
turn with no good match still retrieves nothing rather than the nearest thing.

## Techniques considered and not built

| Technique | Verdict |
|---|---|
| `LanguageModelQueryRouter` | Rejected. Spends an LLM call to decide something the existing triage verdict already answers for free. |
| Re-ranking with a scoring model | Rejected. A second model on the request path, and `ReRankingContentAggregator` discards the relevance scores that make citation possible. |
| `ExpandingQueryTransformer` | Rejected. Multiplies retrieval calls to widen recall on a corpus of a few dozen segments. |
| Hybrid keyword + vector | Rejected. `InMemoryEmbeddingStore` has no keyword index; adding one means adding a store. |
| Chunk metadata (`source`, `title`) | Built. Zero cost, and it is what makes attribution possible. |

## Consequences

**Gained.** Capability and meta questions are answered from written text that a
human reviewed, in a pull request, rather than improvised. Retrieval costs no
money and no network. Every segment carries a source, so the assistant can name
where an answer came from.

**Given up.** Routing now depends on judge quality: a data question the judge
fails to attach a skill hint to will also retrieve, and the threshold is the only
thing limiting the damage. The corpus is only as good as the Markdown, and
nothing yet checks that it stays true as the tool set changes.

## Revisit if

The corpus grows past a few hundred segments, where in-memory rebuild-per-boot
stops being free; or the overlap test starts failing, which would mean the
threshold alone had become sufficient and the router could be deleted.
