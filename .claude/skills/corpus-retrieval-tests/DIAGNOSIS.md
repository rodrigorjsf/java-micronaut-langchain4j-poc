# Which layer failed

Open this the moment a row fails, and before editing anything. A question that
should retrieve and does not fails at one of the four layers [`SKILL.md`](SKILL.md)
names — **the corpus, the chunk boundary, the vocabulary, or the
threshold-or-router** — four causes with four different fixes and four different
owners, indistinguishable from the failing assertion alone. *Layer* means those four
and nothing else, here as there. The rules live in [`SKILL.md`](SKILL.md); this is
the procedure.

**Run the probes in order.** Each one is cheap, each one prints something you can
paste into the commit or the issue, and each one removes a layer from suspicion.
Probe 3 is the one people skip, because it needs a second retriever built by hand
— and it is the only probe that separates a vocabulary failure from a gate
failure, which are the two verdicts most often confused.

The worked question below is the one from `writing-retrievable-knowledge`'s
`WORKED-EXAMPLE.md`, in the same invented domain — a public library's assistant, whose corpus is the
library's own rules. The user typed: *"can I keep this book longer or do I have to
bring it back"*, and the row expects `renewals.md`.

## Probe 0 — is the row right?

Read the row's `provenance` and its date before anything else. A row whose
provenance is a real ticket or a log line is a row about a user who actually asked;
a row somebody wrote from the document is a row that may simply be wrong.

The discriminator is `agentic-evals`'s and is not re-argued here: **would you have
written this row this way before seeing the failure?** If you cannot answer without
reading the diff, the row stands and something below it is at fault. Move on.

## Probe 1 — is the fact in the corpus at all?

Search the **source documents** for the answer's key noun, not for the question's
words. The question says *keep it longer*; the fact is a renewal, so search for
`renew`, `renewal`, `due date`, `three weeks`.

```
grep -rin "renew\|due date" <corpus directory>
```

| What you see | Verdict | Hand it to |
|---|---|---|
| no hit anywhere | **the fact is not in the corpus** | `writing-retrievable-knowledge` — write the section |
| no hit, but the fact is in the system prompt | **never migrated** | `prompt-to-corpus-migration` — its step 1, not a test failure. A model cannot load that skill: ask the user to run it, which they invoke by name |
| a hit | continue to probe 2 | |

The second row is the one people miss on a migration run, and it looks exactly
like a retrieval bug: the answer used to appear, because the prompt was carrying
it, and the corpus never had it.

## Probe 2 — is it whole in one chunk?

Print the chunks **the ingestion actually produced**, not the source file. The
source file is not what was embedded, and the difference between them is the whole
of this probe.

Find the chunk holding the fact, print it alone, and read it with everything above
it deleted — the mechanical test from `writing-retrievable-knowledge`. Print at
minimum the chunk's text, its length, and its source metadata:

```
chunk 7  renewals.md  (412 chars)
"It is applied automatically at the end of the term, provided that no other
 patron has placed a hold on the title and that the account is in good
 standing as described above. …"
```

| What you see | Verdict | Hand it to |
|---|---|---|
| the fact is split across two chunks, or the chunk's subject is a pronoun whose noun stayed behind, or a table's header row is in the previous chunk | **the boundary orphaned it** | `writing-retrievable-knowledge` |
| the chunk is whole and says what it is about | continue to probe 3 | |

Where the ingestion path offers no way to print produced chunks, add one — a
method returning the segment list, a debug flag, a test that ingests one document
in isolation. Every subsequent probe reads better with it, and its absence is why
teams diagnose a boundary failure as a vocabulary failure and rewrite prose that
was fine.

## Probe 3 — open the gate

Build a second retriever over the **same store and the same embedding model**, with
no minimum score and a large k — ten, or the whole store on a small corpus. Run the
failing question through it and print rank, score and source for every hit, and mark
the intended chunk — `expected_source` in the query set, which is the field's only
name. The scores below are invented along with the domain — read your own:

```
rank  score   source
1     0.74    fines.md
2     0.72    renewals.md      <- the intended chunk (the row's expected_source)
3     0.69    holds.md
```

Then read it against the shipped configuration — the top-k and the threshold you
wrote down at check 2 of `SKILL.md`'s closing table.

| What the open-gate run shows | Verdict | Hand it to |
|---|---|---|
| the intended chunk is not in the top ranks at all, or ranks below documents that have nothing to do with the question | **vocabulary** — the document is in the team's words and the query is in the user's | `writing-retrievable-knowledge` |
| the intended chunk ranks first, with a score under the shipped threshold | **the threshold** | `retrieval-that-earns-its-place` |
| the intended chunk ranks inside top-k with a score above the threshold, and the shipped path still returned nothing | **the router** — continue to probe 4 to confirm | `retrieval-that-earns-its-place` |
| the intended chunk ranks just outside the shipped top-k | **a neighbour took the slot.** Read the chunk that outranked it: usually two sections restate the same explanation and both match | `writing-retrievable-knowledge` |

The example above is the first row: `renewals.md` is reachable,
but `fines.md` outscores it on a question about keeping a book longer, which says
the renewal section is not carrying the user's words.

## Probe 4 — replay the routing input

Only reached when probe 3 says the chunk was reachable and the shipped path
returned nothing anyway. Call the router directly with the row's `routing_input`
and print its decision. A row whose `routing_input` is `n/a` — a project with no
router — never reaches this probe; for it, probe 3 was the last rung.

| What you see | Verdict |
|---|---|
| the router declined | the threshold was never consulted. Either the row's `routing_input` is not what the real turn carries — fix the row — or the router's signal is wrong for this class of question, which is `retrieval-that-earns-its-place`'s |
| the router routed | the emptiness came from the threshold after all; return to probe 3's second row |

This probe is also what a negative row's `expected_empty` field is asserting, which
is why that field exists: an unqualified "returned empty" passes whether the router
declined or the threshold cleared the field, and only one of those is the behaviour
you meant to ship.

## What a wrong diagnosis costs

Three, concretely, so the ladder is worth running:

- **Editing a document when the router declined.** The corpus is one section
  larger, the query still fails, and the new section — written to please an
  embedding rather than to answer a user — now competes for a top-k slot with the
  section that was already right. The next failure is on a different row.
- **Lowering the threshold to admit one row.** It admits everything under it for
  every row. Where the relevant and irrelevant score distributions overlap, there is
  no value that admits the one and excludes the others; that is what the overlap
  measurement means.
- **Rewriting prose when the boundary cut it.** The wording was never the problem,
  the rewrite changes the wording, and the chunk is cut in the same place — because
  the section still exceeds the segment budget.

## The inverse: a question that retrieves and should not

Shorter, and it starts from evidence rather than from a search. Print the chunk
that matched and the score it matched at, then read the sentence that matched.

| Probe | What you find | Hand it to |
|---|---|---|
| read the matched chunk | a sentence promising more than the document delivers — "everything about X", "any question regarding Y" | `writing-retrievable-knowledge` — narrow the claim. This is the only fix that makes the corpus better |
| check the router's decision for that turn | it routed although a signal said a tool would answer, or there is no router | `retrieval-that-earns-its-place` |
| compare the score with the threshold | it cleared by a hair, and so would half the irrelevant band | `retrieval-that-earns-its-place` — last, because it moves every row |
| look for the row | there was never a negative row for this question | nobody. It is a hole in the set: write the row now |

Whatever the verdict, the question becomes a permanent negative row before the fix
lands.
