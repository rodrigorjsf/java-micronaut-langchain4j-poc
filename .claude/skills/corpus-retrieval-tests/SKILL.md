---
name: corpus-retrieval-tests
description: Run when a corpus needs the committed query set that proves it answers the questions it was written for, when a question that ought to retrieve does not and the failed layer must be named, or when a corpus document changed and you need the queries it puts at risk.
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
| you are deciding whether the suite gates every commit or runs live | [`WHERE-IT-RUNS.md`](WHERE-IT-RUNS.md) |
| you are working on the repository this skill was written in | [`REPOSITORY-SUITE.md`](REPOSITORY-SUITE.md), then the last section of [`REPORT.md`](REPORT.md) |
| you need more questions than you have | [`SENTENCE-SOURCES.md`](SENTENCE-SOURCES.md) |
| a question that ought to work does not | [`DIAGNOSIS.md`](DIAGNOSIS.md) — do not edit a document first |
| a question retrieves and should not | [`DIAGNOSIS.md`](DIAGNOSIS.md), *The inverse* |
| a new document landed in the corpus | [`CORPUS-GROWTH.md`](CORPUS-GROWTH.md) |
| you edited a document and need the rows it puts at risk, or want to read a run | [`REPORT.md`](REPORT.md) — the report, its coverage table and the diff mode |
| you need row counts, gating, the deterministic/live split, an LLM judge, or the rule for when the row is wrong | `agentic-evals` — that skill owns all of them |
| the document itself is what needs rewriting — how the splitter cuts, what makes a chunk stand alone, whose words it is in | `writing-retrievable-knowledge` |
| the question is whether to retrieve at all, what belongs in the corpus, where the threshold sits, the router's signal, or whether retrieved text is stored in chat memory | `retrieval-that-earns-its-place` |
| the text is still in a prompt and has not been migrated yet | `prompt-to-corpus-migration` — a model cannot load it; ask the user to run it, which they invoke by name |
| the failure is an absent control at a seam — no output guardrail, a catalogue that loads eleven of twelve entries | `agentic-codebase-audit` — a model cannot load it either; ask the user to run it, which they invoke by name |

**Three skills, four steps, one order, and it does not commute:** audit and plan
(`prompt-to-corpus-migration`) → write the document
(`writing-retrievable-knowledge`) → prove it answers (this page) → delete the text
from the prompt (`prompt-to-corpus-migration` again). Reading a document is not
evidence that it retrieves; this page produces the evidence, and until it is green
the prompt keeps its copy.

**Step 1 and step 4 are not a model's to take.** `prompt-to-corpus-migration`
carries `disable-model-invocation: true`, as this page does, so at both hand-offs
**ask the user to run `prompt-to-corpus-migration`, which they invoke by name**, and
leave the step open until they have — reporting a step done because you pointed at
its owner is how the prompt keeps text everyone believes was deleted. The middle
step is different: a model can load `writing-retrievable-knowledge` itself.

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

**One home, one owner.** The query set is committed **beside the corpus** — or,
where the corpus directory is itself read off the classpath and a sibling would
shadow it, beside the repository's other eval datasets ([`REPORT.md`](REPORT.md)
names this repository's case) — and
**this skill owns its schema** — the field table below is that schema, and there is
no second copy of it anywhere. Every document that names the set is naming this one
artifact in that one home: `prompt-to-corpus-migration`'s acceptance list and
`writing-retrievable-knowledge`'s per-document question list are *this* file at
earlier moments in its life, not two other files that later have to be reconciled
with it. A document may point here for the fields; it may not declare a second
location for the set or a second definition of a field.

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
one most often left out. It is also the field's only name. Where another page says
*the intended chunk*, the thing it means is this field, and the phrase to write is
"the intended chunk — `expected_source` in the query set"; the schema above owns the
definition, and nothing else defines it. Without it a row asserts that *something*
came back, and three different failures collapse into one boolean:

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

The repository this skill was written in is the illustration of the gap — two far
negatives, a pinned probe that scored above the shipped gate, and no near-miss half
→ [`REPOSITORY-SUITE.md`](REPOSITORY-SUITE.md).

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

**Write the gate as a count, not a rate.** `agentic-evals` settles this, in its
sizing arithmetic: below roughly twenty negative rows a `<= 0.05` rate permits zero misses, so
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
`prompt-to-corpus-migration` lists where each one hides; a library default nobody set is still the value you are
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

An in-process embedding model means no key and a reproducible number, so the suite
gates every commit; a hosted API means a live suite, tagged out of the default
build and paced, whose split and pacing `agentic-evals` owns. Find out which by
reading the ingest path → [`WHERE-IT-RUNS.md`](WHERE-IT-RUNS.md), with the cost of
caching embeddings.

## Where the sentences come from

The words must come from outside the document. **This skill owns the one
priority-ordered list of sources** — real traffic, the support inbox, the claims
the documents make, the gaps the tool catalogue leaves — plus the variant axes, the
rule that a variant earns a row only when it could fail at a layer you can name,
and the rule that a model may generate variants but never base questions →
[`SENTENCE-SOURCES.md`](SENTENCE-SOURCES.md).

## When a question that should hit does not

**Four layers can fail, and they are these four:**

1. **the corpus** — the fact is not in it at all
2. **the chunk boundary** — the fact is in it, and the chunk the ingestion produced
   around that fact cannot stand alone
3. **the vocabulary** — the chunk is whole, and it is in the team's words while the
   question is in the user's
4. **the threshold or the router** — the chunk ranks first with the gate open, and
   the path the application ships returns nothing anyway

**That is what *layer* means on this page, in `DIAGNOSIS.md`, and in any document
that points here: a place a retrieval can fail.** It is never a category of writing
defect — a document that over-claims, or buries its subject in a pronoun, has a
defect, and the defect is repaired at the chunk-boundary or vocabulary layer. Naming
the defect a layer puts five or six things in a set that has four, and the diagnosis
table stops being exhaustive.

Only one of the four is fixed by editing the document, and two further verdicts —
never migrated, and the row itself is wrong — are not layers at all. Each of the
six verdicts ends in a hand-off, not in a fix.

→ [`DIAGNOSIS.md`](DIAGNOSIS.md) — open it the moment a row fails, before editing
anything: the six verdicts and whose fix each one is, then four probes in order,
what each one prints, and which verdict each output supports.

## A question that retrieves and should not

The same ladder inverted, and shorter: an over-claiming section, a turn that should
not have been routed, the threshold last — or, most often, a negative row nobody
wrote. Whichever it was, the question becomes a permanent negative row before the
fix lands → [`DIAGNOSIS.md`](DIAGNOSIS.md), *The inverse*, and *What a wrong
diagnosis costs* for why the order matters.

## Rows are not edited to make them pass

`agentic-evals` owns the principle and its discriminator. Two edits specific to a
retrieval set are where one dies quietly — widening `expected_source` after seeing
what came back, and raising top-k in the test only → [`DIAGNOSIS.md`](DIAGNOSIS.md),
*Rows are not edited to make them pass*.

## Re-running as the corpus grows

Top-k is a competition, so a new document is a regression risk for old rows. It
owes the set one positive row naming it, one negative in its neighbourhood and a
full re-run; then check both directions, document → row and row → document →
[`CORPUS-GROWTH.md`](CORPUS-GROWTH.md).

## Every run writes a report; a diff names the rows at risk

Have the test write a self-contained HTML report on every run, retrieving **once
per query** and feeding the same results to the assertions and the report. Given a
git ref, the diff mode names, per changed document, the positive rows whose
`expected_source` it is **and every negative row**, since an edit anywhere can lift
a question over the threshold → [`REPORT.md`](REPORT.md): the sections, the pass
rule, the diff rule, and this repository's commands.

## Done when

Run in order; done is the right-hand column.

| # | Check | Done when |
|---|---|---|
| 1 | Find the question list that already exists | you extended `writing-retrievable-knowledge`'s list or `prompt-to-corpus-migration`'s acceptance list — there is one list in the repository, not three, and it is committed beside the corpus. Where the acceptance list is the one to extend and does not exist yet, a model cannot produce it: ask the user to run `prompt-to-corpus-migration`, which they invoke by name, and this row stays open until they have |
| 2 | Read the retrieval configuration you are asserting against | top-k, the threshold, the embedding model and whether a router exists are written down with the file each came from, including any the library defaults and nobody set |
| 3 | Write the positive rows | each names an `expected_source` and the `routing_input` a real turn carries; no row asserts merely that something came back |
| 4 | Write the negative half | every row is in the product's own vocabulary, names which emptiness it asserts, and every tool in the catalogue has been walked for the question it serves; no absurdity stands in for a near-miss |
| 5 | Vary each base question | every variant could fail while its base passes and you can name the layer; misspelling and synonym rows carry the `evasion` family; base questions came from outside the document |
| 6 | Pin the distribution | the lowest relevant top score and the highest irrelevant one are asserted, measured with the gate open, and the assertion says what a flip means |
| 7 | Decide where the suite runs and what it gates | decided by the embedding model, sized off `agentic-evals`'s sizing arithmetic, and the gate written as a count wherever the negative half is under about twenty rows |
| 8 | Run it through the shipped path | the router and threshold the application ships; the only open-gate call in the file is the distribution probe, and a comment says why |
| 9 | Diagnose every failure before editing anything | `DIAGNOSIS.md` ran, the verdict names which of the four layers failed, and the fix went to the skill that owns that layer — no document was edited on a hunch. Where the verdict is *never migrated*, the owner is `prompt-to-corpus-migration` and a model cannot hand off to it: ask the user to run it, which they invoke by name |
| 10 | Check both directions | every corpus document is some positive row's `expected_source`, and every `expected_source` names a document that exists |
| 11 | Read the report after the run | every document appears in the coverage table with at least one passing row, *Uncovered documents* is empty, and after a corpus edit the diff mode ran against the base ref and every row it named was read |
| 12 | Commit the set in its one home | rows in a stable order, each with its provenance; any edited row in its own commit with its reason |

A green run of positives alone closes none of this. The row that certifies a
corpus is the negative one — the question in the product's own words that the
corpus was asked and correctly refused to answer.
