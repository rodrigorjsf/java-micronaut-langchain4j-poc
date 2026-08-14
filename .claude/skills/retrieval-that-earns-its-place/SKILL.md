---
name: retrieval-that-earns-its-place
description: Decide whether and when an agent should retrieve, and prove the threshold works. Use when adding RAG to an agent that already has tools, when choosing a corpus, when tuning a similarity threshold, or when retrieved context is polluting answers.
---

# Retrieval that earns its place

An agent with tools already has a way to get facts. Retrieval has to justify
itself against that, and the two questions it must answer are **what is in the
corpus** and **when does it run**.

## The corpus: what tools cannot answer

**Do not retrieve facts your tools serve.** A corpus of world facts goes stale,
competes with the tools for the model's trust, and gives the model two answers
where it needed one.

Retrieve what has no tool:

- what the assistant can and cannot do;
- why it said "not found";
- domain vocabulary the user's question depends on — what a postal code actually
  covers, what a status field means, how to read a coded value;
- policy, limits, and the shape of the data.

This corpus is small, changes with the product rather than with the world, and
lives in the repository where a human reviews it in a pull request. That last
point is the one that matters: it is documentation the model reads, so it stays
true the same way documentation does.

Metadata on every chunk — `source`, `title` — costs nothing and is what makes
attribution possible.

## A similarity threshold may not separate. Measure before you trust it.

The tempting design is: always retrieve, and let `minScore` filter. Whether that
works is an empirical question about *your* model and *your* corpus, and the
answer is often no.

Measured on one small corpus with a quantized MiniLM, top-hit relevance score:

```
0.8475  "a previsao esta em qual fuso horario?"      relevant
0.7976  "o que voce consegue fazer?"                 relevant
0.7938  "o que quer dizer o codigo 95 na previsao?"  relevant
0.7937  "o que significa o codigo IBGE?"             relevant
0.7469  "quais sao suas limitacoes?"                 relevant
0.7342  "quem ganhou a copa do mundo de 1994"        IRRELEVANT
0.7299  "por que o CEP veio sem rua e sem bairro?"   relevant
0.7058  "qual e a receita de bolo de cenoura"        IRRELEVANT
0.6826  "escreva um script em python"                IRRELEVANT
```

**The distributions overlap.** An irrelevant question outscores a relevant one.
No choice of threshold separates them — so "just use minScore" was not a simpler
design, it was one that could not work.

Run this measurement before choosing. Six relevant questions, three clearly
irrelevant ones, print the top score for each. It takes ten minutes and it
decides the architecture.

**Then pin the overlap as a test.** If a future change of embedding model or
corpus makes the distributions separate, that test tells you the router can be
deleted.

## Routing: use a signal you already paid for

When the threshold cannot decide, something else must. The cheapest router is one
that reads a decision another component already made.

If a classifier runs before the agent — see the triage-gate pattern — it has
already produced an intent or a capability hint. Route on that:

- **a capability was named** → a tool will answer; retrieved prose would only
  compete with it. Skip retrieval.
- **none was named** → the turn is conversational or about the assistant itself,
  which is exactly what the corpus covers. Retrieve.

Cost: zero. The signal came from a model call that already happened.

Pass it through your framework's **per-invocation parameter carrier**, not
through the prompt text. The router needs it; the model does not need to read it
twice.

Keep the threshold as a second gate inside the routed path, so a routed turn with
no good match still retrieves nothing rather than the nearest thing.

## Techniques worth refusing

| Technique | Why not |
|---|---|
| LLM-based query router | Spends a model call to decide something a cheaper component already decided. |
| Re-ranking with a scoring model | A second model on the request path — and re-ranking aggregators typically discard the relevance scores that make citation possible. |
| Query expansion / multi-query | Multiplies retrieval calls to widen recall on a corpus of a few dozen segments. |
| Hybrid keyword + vector | Needs a store with a keyword index. Adding one is adding infrastructure. |
| A vector database | For a corpus under a few thousand chunks, an in-process store rebuilt at boot is faster, free, and cannot drift from the files in the repository. |

Each of these is right at some scale. State the scale where you would adopt it,
so the refusal is a decision rather than an omission.

## Do not store retrieved content in chat memory

Many frameworks default to writing retrieved chunks into the persisted
conversation. That inflates every stored item and replays the same prose into
every later prompt of the session.

Retrieval is cheap enough to redo per turn. Storing it is not. Find the flag and
turn it off.

## Warm the model at startup

An in-process embedding model can take seconds to load — measured at **5.7 s**
for a quantized MiniLM. Load it eagerly at boot. Lazily, one unlucky user pays
the entire cold start on a request that looked like everyone else's.
