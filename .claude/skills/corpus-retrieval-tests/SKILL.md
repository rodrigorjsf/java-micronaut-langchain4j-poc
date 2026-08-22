---
name: corpus-retrieval-tests
description: Build the committed query set that proves a corpus answers the questions it was written for, and diagnose which layer failed when one of them stops working.
disable-model-invocation: true
---

# Proving a corpus answers

A retrieval failure is silent by construction. Nothing throws, no call returns a
non-2xx, no assertion in the existing suite goes red. The retriever hands the
model three chunks that do not contain the answer — or hands it nothing — and the
model writes a confident paragraph out of whatever else is in the prompt. The
suite people write against this is a list of questions that **should** hit, and
that suite is passed by a corpus that returns its nearest chunk for every input,
including inputs it has no business answering. The measurement that decides
whether retrieval works at all is the one nobody writes down: what comes back for
the questions that must retrieve **nothing**.

| You are here because | Start at |
|---|---|
| you are building the query set for the first time | *The query set is a committed file, not test code* |
| your suite is all positives and passes | *The negative half is the load-bearing half* |
| you are deciding what a test should actually assert | *What to assert* |
| you need more questions than you have | *Where the sentences come from* |
| a question that ought to work does not | [`DIAGNOSIS.md`](DIAGNOSIS.md) — do not edit a document first |
| a question retrieves and should not | *A question that retrieves and should not* |
| a new document landed in the corpus | *Re-running as the corpus grows* |
| you need row counts, gating, or the deterministic/live split | `agentic-evals` — that skill owns all three |
| the document itself is what needs rewriting | `writing-retrievable-knowledge` |
| the question is whether to retrieve at all, or where the threshold sits | `retrieval-that-earns-its-place` |

Four skills, one order, and it does not commute: **audit and plan
(`prompt-to-corpus-migration`) → write the document
(`writing-retrievable-knowledge`) → prove it answers (this page) → only then
delete the text from the prompt.** Reading a document is not evidence that it
retrieves; this page produces the evidence, and until it is green the prompt keeps
its copy.

## The query set is a committed file, not test code

The rows live in a data file — CSV, TSV, JSON, YAML, a table in a Markdown file —
and the test iterates it. Two reasons, both practical. Somebody who can write a
question cannot necessarily write an assertion, and a suite only grows if adding a
row is adding a line. And a diff of that file reads as **a change to what the
corpus promises**, which is exactly the thing you want a reviewer to see; a diff
of test code reads as test maintenance and gets skimmed.

**It is not a new list.** `writing-retrievable-knowledge` commits a question list
beside each document, and `prompt-to-corpus-migration` commits an acceptance list
at its step 0, before the document exists. Extend whichever exists. A second list
nobody reconciles is how a question passes in one file and is unknown to the other.

| Field | Why it is there |
|---|---|
| `id` | a failure names a row somebody can open. Stable, never renumbered |
| `question` | the user's words, not the document's — see *Where the sentences come from* |
| `label` | `positive` or `negative`. There is no third label; "probably fine" is a row nobody can act on |
| `expected_source` (positive) | the document — or the chunk, where your store addresses chunks — that must come back |
| `expected_empty` (negative) | *which* emptiness this row asserts: the router declined, or it routed and nothing cleared the threshold |
| `routing_input` | whatever the real turn carries into the router — an intent label, a capability hint, a classifier verdict. `none` means the turn carried no signal, which in most designs is the thing that makes it retrieve at all; `n/a` means this project has no router. The two are not interchangeable |
| `variant_of` | the base row this one paraphrases, so six rows failing together read as one bug |
| `families` | the tags an assertion selects on, `agentic-evals`'s shape — `evasion` for a misspelling or synonym row, `complaint` for a row that came from a real reported failure |
| `provenance` and date | where the question came from — a log line, a ticket, the document's own claim — written the day the row was created |

**`expected_source` is the field that makes a failure diagnosable**, and it is the
one most often left out. Without it a row asserts that *something* came back, and
three different failures collapse into one boolean:

```
nothing came back                → the corpus, the wording, or the gate
the wrong document came back     → two sections compete; usually one over-claims
the right document came back
  below the top-k cut            → a neighbouring document took the slot
```

Each has a different fix and a different owner. A green `isNotEmpty` distinguishes
none of them, and — worse — it stays green on a corpus that returns its nearest
chunk for every input, which is the failure this whole page exists to catch.

**`routing_input` is not optional where a router exists.** A row that calls the
retriever directly proves the chunk is reachable; it does not prove the turn
reaches it, and that is the only thing a prompt deletion depends on — the reason
`prompt-to-corpus-migration`'s step-2 gate reads *through the router the
application runs, at the threshold the application ships*. Where the project has no
router, write `n/a` — and know that you wrote it, because it is the field that says
the suite is exercising a shorter path than the one you will later be asked about.

## The negative half is the load-bearing half

A positive-only suite measures recall on a retriever that has no way to fail. Add
one section that repeats the corpus's vocabulary loosely enough and every positive
still passes while the corpus has started answering questions that belong to a
tool, to another product area, or to nobody.

**A negative row is a question in the product's own vocabulary that must retrieve
nothing at the configured threshold.** Not an absurdity. A question drawn from a
different universe proves the floor — that the store is not returning literally
everything — and nothing about the boundary you actually ship.

This repository's own suite is the illustration, and it is an illustration of the
gap: the two committed negatives in `rag/KnowledgeBaseTest.java` are a cake recipe
and a request to write a Python script, both plainly out of domain. What such rows
are worth is in the measurement recorded in `rag/SkillAwareQueryRouter.java`'s
javadoc — a third out-of-domain question, a football result measured but never
added to the suite, scored **0.7342** against a relevant question's **0.7299**.
Even the far negatives sat inside the relevant band. Those two rows are the floor;
that suite has no near-miss half yet.

Four kinds of near-miss, all of them in the product's words:

| Kind of negative | The question | What its failure means |
|---|---|---|
| **the tool's question** | in-domain vocabulary, but the answer is live data a tool serves | the corpus is competing with a tool, and the model now has two answers where it needed one |
| **the neighbouring area** | a subject next to the corpus that it deliberately does not cover | a section's claim is wider than its content |
| **the deleted section** | something the corpus used to answer and no longer does | a stale chunk survived an edit, or the store was not rebuilt |
| **same noun, other intent** | the corpus's own subject word in a request it does not serve | the embedding is keying on the noun, not on the question |

The first kind is the sharpest and the easiest to find: **every tool in the
catalogue is a negative row generator.** Anything a tool answers is, by
`retrieval-that-earns-its-place`'s rule, not corpus material — so the question that
reaches that tool is a question the corpus must decline.

**Write the gate as a count, not a rate.** `agentic-evals`'s `SIZING.md` settles
this: below roughly twenty negative rows a `<= 0.05` rate permits zero misses, so
a percentage disguises a zero tolerance as an allowance. A three-document corpus cannot reach twenty
negatives without padding, and padding is the one thing that makes the number
worse. That is also the honest answer to *"generate a large set"*: **what scales a
corpus suite is sections × question-intents, not rows.** A hundred rows over six
sections measures six things a hundred times.

## What to assert

Four assertions. Run every one of them through the path the application ships —
the same router, the same top-k, the same threshold — with exactly one exception,
named in the third row.

**Read those three out of the project first.** Top-k, the threshold and the
router's signal live somewhere different in every stack, and
`prompt-to-corpus-migration`'s *Read the retrieval configuration first* table lists
where each one hides; a library default nobody set is still the value you are
asserting against, so read it for the version in the lock file and write it down
with its provenance. A suite that hard-codes its own copy of these numbers is green
about a system nobody runs.

| # | Assert | The regression it catches |
|---|---|---|
| 1 | the row's `expected_source` appears **within top-k**, and record the rank it appeared at and its score | a neighbouring document taking the slot; a document renamed; a section that stopped matching. Recording the rank is what shows you the row moved from 1 to 3 **before** it moves to 4 and goes red |
| 2 | a negative row returns empty, through the shipped router at the shipped threshold, in the manner its `expected_empty` names | a corpus that has started answering everything; and a router that declined for the wrong reason, which an unqualified "empty" would have passed |
| 3 | the score **distribution** is pinned: the lowest top score among relevant questions against the highest among irrelevant ones. This is the one probe that runs with the gate open — no minimum score, k of 1 — because the numbers that decide it live below the threshold | a change of embedding model or corpus that makes the distributions separate. A flip means the threshold now does the job alone and **the router may be deletable**; a widening overlap means the opposite. `retrieval-that-earns-its-place` owns why the overlap defeats a threshold — this is the assertion that watches it |
| 4 | every returned chunk carries the attribution metadata the answer cites — the source, the title | metadata dropped in an ingestion refactor. Assertion 1 depends on this field existing at all, so it fails first and tells you why |

**Do not assert the model's final answer text here.** It makes a retrieval
regression look like a wording change, and it puts a judge in a suite that did not
need one. What the answer does with retrieved chunks is `agentic-evals`'s ground.

→ [`QUERY-SET.md`](QUERY-SET.md) — open it when writing the first rows: a worked
set in a neutral domain with every field filled, and the four assertions written
as prose a reader can implement in any framework.

## Where this suite runs is decided by the embedding model

Not by preference, and not by where the other tests happen to live.

**An in-process embedding model means no key, no network and a reproducible
number**, so the suite is an ordinary unit test that can gate every commit. The
figures recorded in this repository's `rag/EmbeddingModelFactory.java`, as an
illustration of the shape and not as a target: a quantized MiniLM on ONNX Runtime,
~14 ms per embedding and ~5.7 s to load. Per row that is free; per suite start it
is a fixed several seconds, which is an argument for one suite that loads the model
once, not for fewer rows.

**A hosted embedding API means a key, a bill and somebody else's rate limit**, so
it is a live suite: tagged out of the default build, run before a release, paced.
`agentic-evals` owns that split and the gating that follows from it, and its
`RUNNING-A-SUITE.md` owns the pacing.

**Find out which you have by reading the ingest path**, where the embedding model
is constructed: a model id string plus credentials is hosted; weights loaded from
a file or a bundled runtime are in-process. A framework default counts as a
decision — read it for the version in the lock file.

**The snapshot temptation, and its cost.** Caching embeddings to make a hosted
suite deterministic pins the vectors, and the suite then cannot notice that the
model changed under you — one of the two regressions it exists to notice. If you
cache, put the model id and version in the cache key and fail the run on a
mismatch rather than silently reusing yesterday's vectors.

Row counts, the band around a score, and how small a regression a suite can see at
all are `agentic-evals`'s `SIZING.md`. Cite it; do not invent a competing floor.

## Where the sentences come from

The set is only as good as the words in it, and the words must come from outside
the document. A question written by the person who wrote the document reuses the
document's vocabulary and matches by construction — a rigged green, and the same
failure `writing-retrievable-knowledge` names for its own question list.

In priority order:

| Source | What it gives you | What it cannot give you |
|---|---|---|
| **real traffic and query logs**, where they exist and privacy allows | the user's actual phrasing, including the phrasing nobody would have predicted | anything about a document nobody has had reason to ask for yet |
| **the support inbox** | ticket subject lines, unedited, skewed towards the questions that already failed — which is the skew you want | what already worked, so it will not tell you what to keep |
| **the claims the documents make** | one row per section, mechanically, with a definite end. This is the coverage direction: every section must be some row's `expected_source` | the user's words. These rows need the variants below more than any others |
| **the gaps the tool catalogue leaves** | the questions in the domain that no tool serves — which is precisely the corpus's ground. The ones a tool **does** serve become your best negatives | nothing; run it in both directions and it is the cheapest source here |

Then vary each base question, because one phrasing tests one phrasing:

| Axis | The shape | The layer it can break |
|---|---|---|
| paraphrase | different content words, same intent | vocabulary — the failure `writing-retrievable-knowledge` fixes by putting the user's words in the document |
| the terse typed form | no verb, no punctuation, three words | whether matching survives without the sentence around it |
| misspelling, missing accents, wrong plural | one character off | tag `evasion` — `agentic-evals` asserts that family row by row rather than averaging it in |
| abbreviation, and its expansion | both forms, at least once each | vocabulary, in the place a corpus most often has only one of the two |
| the other language the product serves | the same question, wholly in it | whether the corpus covers that language at all — a whole-language failure is a content finding, not a row finding |
| the polite wrapper | "could you please tell me…" around the same content words | usually nothing. See below |

**A variant earns a row when it could fail while its base row passes, and you can
name the layer it would fail at.** If you cannot name the layer, it is a duplicate:
it costs runtime, it moves the denominator every rate divides by, and it dilutes
the family assertions that were the sharpest thing in the file. Politeness wrappers
are the usual duplicate — the content words are identical, so the embedding barely
moves.

**A model may generate variants; it may not generate base questions.** Paraphrasing
a real user turn keeps the user's content words and is exactly what generation is
good at. Generating questions from the document reproduces the document's
vocabulary at scale, and a hundred such rows are a hundred copies of the same
rigged green.

Enough is: every section has one base question, plus a variant on the axis that
section is most exposed to — the abbreviation if it has one, the other language if
the product serves one, the terse form if users type at speed. Rows past that add
runtime and no resolution.

## When a question that should hit does not

**Four layers can fail, and only one of them is fixed by editing the document.**
Two further verdicts are not layers at all — the fact was never migrated, and the
row itself is wrong — which is why the table below has six rows. Diagnose before
you touch anything; each verdict ends in a hand-off, not in a fix:

| Verdict | The observation that produces it | Whose ground the fix is |
|---|---|---|
| the fact is not in the corpus at all | it is not in the source documents | `writing-retrievable-knowledge` — write it |
| …and it is still in the prompt | it is in the standing text, never migrated | `prompt-to-corpus-migration` — this is its step 1, not a test failure |
| the chunk boundary orphaned it | it is there, and the produced chunk holding it cannot stand alone | `writing-retrievable-knowledge` |
| the vocabulary is the team's, not the user's | the chunk is whole, and is not in the top ranks even with the gate open | `writing-retrievable-knowledge` |
| the threshold or the router discarded it | it ranks first with the gate open, and the shipped path returns nothing | `retrieval-that-earns-its-place` |
| the row is what is wrong | you would have written the row differently before seeing the failure | `agentic-evals` |

**A wrong diagnosis is not a neutral detour.** Editing a document when the router
declined leaves the corpus one section larger, the query still failing, and that
new section now competing for a top-k slot with the section that was already
right. Lowering a threshold to admit one row admits everything under it for
**every** row — and the overlap measurement says the band below is not empty.

→ [`DIAGNOSIS.md`](DIAGNOSIS.md) — open it the moment a row fails, before editing
anything. Four probes in order, what each one prints, and which verdict each output
supports.

## A question that retrieves and should not

The same ladder inverted, and it is shorter. Print the chunk that matched and read
the sentence that matched, then work down:

1. **A section over-claims.** A sentence promising more than the document delivers
   — "everything about X", "any question regarding Y" — matches the whole
   neighbourhood. Narrowing the claim is the first fix and the only one that makes
   the corpus better; it is `writing-retrievable-knowledge`'s.
2. **The turn should not have been routed at all.** The router had a signal saying
   a tool would answer and routed anyway, or there is no router.
   `retrieval-that-earns-its-place`.
3. **The threshold.** Last, always, because it moves every row — and because the
   overlap measurement says there is no value that separates a band that overlaps.
4. **Nobody ever asserted it.** The commonest cause: the question was never a
   negative row. That is not a bug in the corpus, it is a hole in the set.

Whichever it was, **the question becomes a permanent negative row before the fix
lands** — `agentic-evals`'s rule for reported false positives, and a corpus
reproduces the same failure just as reliably.

## Rows are not edited to make them pass

`agentic-evals` owns this principle and its discriminator — *would you have
labelled it this way before seeing the failure* — and the requirement that a row
change lands in its own commit with its reason. Do not re-argue it. Two edits are
specific to a retrieval set and are where one dies quietly:

**Widening `expected_source` after seeing what came back.** "It returned B, and B
is also fine" is a change to what the corpus promises, made in the one moment you
are least able to judge it. Sometimes it is right — two documents genuinely both
answer. It goes in its own commit with the reason, or the field degrades into
*whatever the retriever returned last time*, and the suite certifies nothing while
still printing a number.

**Raising top-k in the test only.** A row that passes at rank 4 in a suite whose
application ships a top-k of three tests a system nobody runs. If the answer needs four
chunks, that is a configuration change for `retrieval-that-earns-its-place`, made
in the application, and every other row re-measured against it.

## Re-running as the corpus grows

**Top-k is a competition, so a new document is a regression risk for old rows.**
The document under test is not where the failure lands; it lands on the row that
used to be rank 3. Two obligations follow.

A new document owes the query set: at least one positive row naming it, at least
one negative in its neighbourhood — the question next to it that it must not
answer — and **a full re-run of the whole set**, not just its own rows. The rank
recorded by assertion 1 is what makes the slow version of this visible: a row that
moved from rank 1 to rank 3 is a warning, and the run after next is where it goes
red.

**Then check both directions**, the habit this repository already runs at another
seam — `scripts/check-tool-catalogue.py` fails on a key present in the code and
absent from the configuration **and** on the reverse. Borrow the shape, not the
script:

- **document → row.** A document that is no positive row's `expected_source` is
  either dead weight or a missing row, and you cannot tell which without asking.
  In this checkout that direction holds: the six positive rows in
  `KnowledgeBaseTest` name all three files under `src/main/resources/knowledge/`,
  two rows each.
- **row → document.** An `expected_source` naming a file that no longer exists.
  After a rename the row can never pass, and the repair somebody reaches for under
  time pressure is relaxing the assertion — which is the previous section's failure
  arriving through a door nobody was watching.

## Not this skill's ground

| The question | Whose |
|---|---|
| whether to retrieve at all, what belongs in the corpus, where the threshold sits, the router's signal, whether retrieved text is stored in chat memory | `retrieval-that-earns-its-place` |
| how the splitter cuts, what makes a chunk stand alone, how a document is worded so the user's phrasing matches it | `writing-retrievable-knowledge` |
| deterministic versus live, what the build gates on, how many rows a gate needs, an LLM judge, and the rule for when the row is the thing that is wrong | `agentic-evals` |
| content sitting in a prompt that should be in the corpus, and when the prompt text may finally be deleted | `prompt-to-corpus-migration` |
| an absent control at a seam — no output guardrail, a catalogue that loads eleven of twelve entries | `agentic-codebase-audit` |

## Done when

Run in order; done is the right-hand column.

| # | Check | Done when |
|---|---|---|
| 1 | Find the question list that already exists | you extended `writing-retrievable-knowledge`'s list or `prompt-to-corpus-migration`'s acceptance list — there is one list in the repository, not three |
| 2 | Read the retrieval configuration you are asserting against | top-k, the threshold, the embedding model and whether a router exists are written down with the file each came from, including any the library defaults and nobody set |
| 3 | Write the positive rows | each names an `expected_source` and the `routing_input` a real turn carries; no row asserts merely that something came back |
| 4 | Write the negative half | every row is in the product's own vocabulary, names which emptiness it asserts, and every tool in the catalogue has been walked for the question it serves; no absurdity stands in for a near-miss |
| 5 | Vary each base question | every variant could fail while its base passes and you can name the layer; misspelling and synonym rows carry the `evasion` family; base questions came from outside the document |
| 6 | Pin the distribution | the lowest relevant top score and the highest irrelevant one are asserted, measured with the gate open, and the assertion says what a flip means |
| 7 | Decide where the suite runs and what it gates | decided by the embedding model, sized off `agentic-evals`'s `SIZING.md`, and the gate written as a count wherever the negative half is under about twenty rows |
| 8 | Run it through the shipped path | the router and threshold the application ships; the only open-gate call in the file is the distribution probe, and a comment says why |
| 9 | Diagnose every failure before editing anything | `DIAGNOSIS.md` ran, the verdict names the layer that failed, and the fix went to the skill that owns that layer — no document was edited on a hunch |
| 10 | Check both directions | every corpus document is some positive row's `expected_source`, and every `expected_source` names a document that exists |
| 11 | Commit the set beside the corpus | rows in a stable order, each with its provenance; any edited row in its own commit with its reason |

A green run of positives alone closes none of this. The row that certifies a
corpus is the negative one — the question in the product's own words that the
corpus was asked and correctly refused to answer.
