---
name: prompt-to-corpus-migration
description: Find the domain content shipping in every prompt whether or not the turn needs it, and produce a ranked plan for moving what may move into a retrievable corpus and deleting it from the prompt. No block may be deleted until its proof runs, and that proof is a skill a model cannot load — ask the user to run corpus-retrieval-tests, which they invoke by name.
disable-model-invocation: true
---

# Migrating prompt content into a corpus

A system prompt is the one artefact in an agentic codebase that only grows. Every
feature ships a paragraph into it, no paragraph is ever charged rent, and no build
fails when the eleventh product description arrives — a longer string is still a
valid string. The costs are paid where no diff shows them: on every turn that
carries the paragraph and never needs it, and in the answer where the model
reached for the fact sitting in front of it instead of the tool that would have
fetched today's version of it.

`agentic-codebase-audit` hunts an **absent control at a seam**. This pass hunts
the opposite noun: **present content in the wrong place**. Nothing here is
missing. Everything here is loaded, was correct the day it was written, and is in
a container that ships it to a model that did not ask.

| You are here because | Start at |
|---|---|
| the prompt has grown and nobody can say what is in it | *The noun, and how to inventory it* |
| a block is in front of you and you must decide whether it may leave | *The test for what may move* — the section this skill exists for |
| you know what moves and want to start writing documents | not yet: *Read the retrieval configuration first*, then `writing-retrievable-knowledge` |
| the corpus answers the question and the prompt still carries the block | *The removal half* |
| a document you migrated is never retrieved | *The check runs in both directions* |
| this ran before | *Running it again* |
| the question is whether to retrieve at all, or what a corpus is for | `retrieval-that-earns-its-place` — not this skill |

**Three skills, four steps, one order, and it does not commute:** audit and plan
here → write the document (`writing-retrievable-knowledge`) → prove it answers
(`corpus-retrieval-tests`) → only then delete the text from the prompt, back here,
and re-measure. The audit and the deletion are this skill's; the two in the middle
are not, and one of those a model cannot start on its own —
`corpus-retrieval-tests` carries `disable-model-invocation: true`, as this skill
does, so the proof happens when you ask the user to run corpus-retrieval-tests,
which they invoke by name. Deleting before proving is the failure this whole
document is built around.

## The noun, and how to inventory it

**Standing text** is anything assembled into the prompt of every turn regardless
of what the turn is about. Its unit is a **block**: a contiguous piece with one
subject, which is the thing you move, keep or split.

Frameworks assemble it differently, so locate by **shape**, never by keyword.

| Where it hides | Find it by shape |
|---|---|
| the system message's own paragraphs | a multi-line string literal, or a template file, concatenated before the call |
| a template on disk | a file whose contents nothing parses — it is interpolated and sent |
| tool and function descriptions | the prose beside each declared tool; it ships whether or not the tool is called |
| a skill or instruction set that is always mounted | one whose activation condition is absent or always true — an "always" skill is standing text wearing a routing costume |
| few-shot examples | hard-coded conversation turns pasted in ahead of the real one |
| enum and schema documentation | descriptions on parameters and enum members; they travel inside the tool schema |
| a policy string in configuration | a long value in a config file that reaches the prompt untouched |
| a retrieved block that is always retrieved | retrieval with no router and no threshold is standing text with an embedding step in front of it |

**The inventory is the bytes on the wire, not the source.** A codebase you have
never seen assembles the prompt somewhere you will not guess, and the assembly you
find by reading may not be the only one. So:

1. **Capture one real request's payload** — the provider SDK's debug logging, a
   proxy, or a listener on the client. Whatever the mechanism, the artefact is the
   exact string the model received on one ordinary turn.
2. **Grep a distinctive sentence back.** Take a sentence out of the payload that
   would appear nowhere else, and search the repository for it. That lands you on
   the file that emitted it, and repeating it per block gives every block a
   `file:symbol` origin. (`authoring-agent-skills` uses the same move in the other
   direction, to prove a mounted skill body reached the model at all.)
3. **Account for the remainder.** Text in the payload you could not grep back came
   from a dependency, a framework default or a stored conversation. Say which; an
   unattributed remainder is a container you have not found.

If the repository has a committed `docs/agentic-audit.md`, its **prompt assembly**
row already names the assembly point, and a row naming two of them is telling you
the inventory has two containers before you start. That audit scores whether
configuration or user data reaches the standing instructions; it never asks
whether the content *belongs* there. That question is this document's.

## What it costs, computed

Three inputs per block, two of them cheap:

```
B  bytes the block adds to the prompt           length of the block, from the payload
C  share of turns that carry it                 1.00 for standing text, by definition
U  share of carrying turns whose answer it changes

waste = B × C × (1 − U)   bytes shipped per turn for nothing
```

The arithmetic is the whole point and it is arithmetic, not a benchmark: at
`C = 1.00` and `U = 0.10`, nine tenths of `B` ships on every turn and does no
work; halving `B` halves the waste, and getting `U` to 1.00 removes it entirely,
which is what the block staying in the prompt means.

**`C` is 1.00 by definition and is not a per-row column.** It is in the formula
so that a block which is only *nearly* standing — carried by one request route of
three, or by one locale's assembly — can be costed on the same line as the rest.

**`U` is the one people guess, and the guess is always generous.** You cannot
measure it without labels. What you can do instead is state the qualifying
condition in one sentence — *this block changes the answer when the user asks
about X*. If you cannot write that sentence, `U` is not small, it is **unknown**,
and the next section's second question has already failed. Write `U unknown` in
the ledger rather than a number; a fabricated share is the one entry that gets an
otherwise correct plan thrown out.

**The second cost is not bytes and does not shrink when the block does.** Text
that is present and irrelevant competes for the model's attention with the text
that matters, and a prompt that answers a question the turn did not ask teaches
the model to answer questions nobody asked: the returns policy in front of the
model during a shipping turn comes back in the answer.

**The third cost is the one that produces wrong answers rather than slow ones.** A
fact in the prompt is the model's closest and most trusted context, so it outranks
the tool that would have returned the current value. A stale fact in the prompt is
worse than an absent one — absent, the model asks a tool or says it does not know;
present, it asserts last quarter's number with the prompt's authority.

## The test for what may move

Routing is a bet that something can tell a qualifying turn from a non-qualifying
one **before the answer exists**. Content that must shape every answer loses that
bet by construction: the qualifying condition is *always*, so there is nothing to
detect, and — say — a router that fires on 97% of turns has bought nothing while
failing silently on the other 3%.

This repository decided that against itself, and it is the sharpest statement of
the rule available. Its README's *why not* table keeps the brand voice document in
the prompt rather than behind a skill, because "the rule applies to *every*
answer. Routing buys nothing when the answer is always, and it fails silently when
the model does not notice the turn qualifies."

**Four verdicts, and the set is closed:** `stays`, `moves`, `split`, `neither`.
Two checks run before the three questions and are what make the last two
reachable at all — skip them and a duplicated block is filed as `stays`, where
nobody looks at it again, and a two-subject block is ranked as one thing that it
is not.

| Before the questions | If it fires |
|---|---|
| **S — one subject?** Does the block hold two subjects that would answer Q1 differently: a form rule beside a fact, a policy beside an example? | **split.** Cut it, then run each half through S, D and the three questions before **either** half is ranked. `split` is not a resting verdict — it is a row that must become two |
| **D — is this content already somewhere the model reads?** A tool's own description, another block, an always-mounted skill body | **neither.** Delete it here and fix it there. Not `stays`: nobody re-examines a `stays` row, and two descriptions of one thing is the two-answers drift inside a single turn |

Then run three questions against one block, in order:

| # | Ask of the block | Yes | No |
|---|---|---|---|
| 1 | Does it constrain the **form** of every answer — voice, refusal policy, safety rule, output or citation contract, language? | **stays.** Stop here | go to 2 |
| 2 | Can you state the condition under which a turn needs it in one sentence, **and** does a signal available before the answer carry that condition? | go to 3 | **stays**, and the finding is the missing signal, not the block |
| 3 | With the block absent and nothing retrieved, does the model say it does not know, or invent something plausible? | *says* — **moves** | *invents* — **moves**, ranked above the loud ones |

**Question 3 changes the system to see what it does** — the block has to be gone
before the model can answer without it. Remove it on a scratch branch and revert,
the way `agentic-codebase-audit` runs its probes. A Q3 answered from imagination
ranks the row on a guess.

**The two errors are not equally recoverable, so they do not get the same
benefit of the doubt.** Wrongly kept costs bytes and a drift risk, and next run
fixes it. Wrongly moved costs a rule that used to hold: it is not violated, it is
*absent* — nothing throws, the router simply did not fire, and you learn about it
from a customer or from a brand review a quarter later. **When question 2 is
arguable, the block stays.**

Most blocks resolve on sight; these are the categories and the reason, not a
shortcut around the three questions:

| Block | Verdict |
|---|---|
| brand voice, tone, persona | stays — question 1 |
| refusal policy, safety rules, escalation rules | stays — question 1, and its failure is silent |
| output format, citation format, response language | stays — question 1 |
| how to choose between tools that are always mounted | stays in the prompt only if nowhere better; it belongs in the tool descriptions, which is `agentic-tool-boundary`'s ground |
| product, feature or catalogue descriptions | moves |
| domain vocabulary — what a field means, how to read a coded value | moves |
| policy attached to a class of question — eligibility, limits, SLAs | moves |
| what the assistant can and cannot do, and why it said "not found" | moves, and this is the corpus material `retrieval-that-earns-its-place` argues earns its place |
| few-shot examples | **split** at S: an example teaching **form** stays; an example teaching **content** is a fact in a costume — move the fact and delete the example |
| tool prose duplicating that tool's own description | **neither** at D — delete it here and fix the description |

## Read the retrieval configuration first

The chunker decides the shape of what you write, so reading it after the plan
produces a plan measured in blocks and a corpus measured in chunks, with no
correspondence between them. Six settings, and in every stack they live somewhere
different:

| Read | Where it hides | What it decides |
|---|---|---|
| segment size and overlap | as often a constant beside the ingestion loop as a config file | whether a block is one document or five, and where a heading may go |
| the embedding model, and whether it runs in-process | the store's construction, usually a model id string | which vocabulary matches, and what re-embedding costs when you edit |
| top-k | config | how many chunks compete to be the answer |
| the score threshold | config | whether a correct chunk that scores just under counts as retrieved at all |
| whether a router exists, and on which signal | there may be none — then retrieval runs on every turn, and *is* standing text | whether question 2 above is answerable in this system |
| what metadata rides each chunk | the ingestion loop | whether an answer can cite its source |

**The six are a committed block, not a scratch note.** They are the ledger's
first block, at the stable path — one line each, carrying the value with its
unit where it has one, the file it was read from, and the value the previous run
recorded — because the documents you are about to write are written against
them. A segment size that moves from 800 to 1,200 between runs re-chunks every
document already migrated, and a ledger that never held the old number cannot
tell you it moved: you read 1,200 on run 2, find it plausible, and the corpus is
mis-chunked with nothing red. The record is also what
`writing-retrievable-knowledge` reads on a migration run, rather than deriving
the same numbers a second time out of the same files.

**A default nobody set is still a decision.** Where you find no size, no overlap
and no threshold, the library's defaults are in force — go and read them in its
documentation and write them into that block, with the library, its version from
the lock file, and the page you read as the provenance. An unstated default is the
value most likely to change under you on a minor version bump.

One repository's values, as an illustration of what these entries look like when
found — **not** as numbers to copy. Measured on one small Portuguese corpus (three
Markdown files, 118 lines by `wc -l`) with a quantized MiniLM embedding model:
`rag/KnowledgeBase.java` splits at 600-character segments with 100 of overlap, and
its comment says why — "Small enough that a retrieved segment is mostly signal,
large enough to keep a heading with the paragraph under it. The overlap exists so
a fact split across a boundary survives in one of the two halves." Retrieval in
`application.yml` under `agentic.rag` reads `max-results: 3`, `min-score: 0.72`.
Different corpus, different language, different model, different numbers.

Whether a threshold separates relevant questions from irrelevant ones is
**measured, not assumed** — on that corpus the distributions overlapped, which is
why a router exists at all. The measurement and what to do when it fails to
separate are `retrieval-that-earns-its-place`'s; do not re-derive them here. What
this skill takes from it is one consequence: a proof gate may never read "the
score looked high".

This document stops at the configuration. **How the splitter cuts, what makes a
chunk survive alone, and how a document is worded so a user's phrasing matches it
are `writing-retrievable-knowledge`'s**, and that is the next thing you open.

## The removal half

A migration that only adds is not a migration. A fact in the prompt **and** in the
corpus is worse than the same fact in either one alone: the two drift, the model
gets two answers, and the prompt's copy wins — so the document you just wrote costs
an embedding pass and changes nothing.

Five steps per block, with a gate before each one. The gates are the document.

| # | Step | Gate before the next step |
|---|---|---|
| 0 | **Write the acceptance list** — the questions this block used to answer, in the words users type | the list is committed **before** the document exists, as rows in the one query set committed beside the corpus, whose schema `corpus-retrieval-tests` owns. It is not a second list at a path of this skill's own |
| 1 | **Write the document** — `writing-retrievable-knowledge` | every question on the list has an intended chunk you can name — the intended chunk is `expected_source` in the query set |
| 2 | **Prove it answers** — `corpus-retrieval-tests`, which a model cannot load: ask the user to run corpus-retrieval-tests, which they invoke by name | every question retrieves its `expected_source`, **through the router the application runs, at the threshold the application ships** |
| 3 | **Delete the block** | the deletion is its own commit and changes nothing else |
| 4 | **Re-measure** | a **freshly captured payload** does not contain the block, and the end-to-end answers to the acceptance list are still right |

**Step 0 is first because a list written afterwards is graded against itself.**
Write the document first and the questions come out of its own vocabulary, so
every one of them passes and the suite proves nothing. Draw the questions from
real turns wherever logs have them — where the sentences come from is one
priority-ordered list, owned by `corpus-retrieval-tests`, and this skill points at
it rather than printing a second one.

**Step 2's gate says "through the router" for a reason.** A proof run with the
router bypassed proves the chunk exists. It does not prove the turn reaches it,
which is the only thing the deletion depends on.

**Step 3 is its own commit** because a deletion bundled with the document cannot
be reverted without also reverting the corpus, and the first production surprise
wants exactly that revert and nothing else.

**What stays behind is at most a capability line** — one sentence saying the
capability exists so the model knows to look — and **never a restatement of a
fact**. A pointer carrying one fact is the two-answers drift at one-line scale.

### When step 2 fails, which is not "delete anyway"

A query that misses has **four layers** that can be at fault — corpus, chunk
boundary, vocabulary, threshold-or-router — and, checked ahead of all four, the row
itself, which is not a layer. Which one it was is diagnosed by
`corpus-retrieval-tests`, and that diagnosis is not re-derived here; that skill is
one a model cannot load either, so the diagnosis happens when you ask the user to
run corpus-retrieval-tests, which they invoke by name. What this skill owns is what
the migration does with each verdict:

| The diagnosis | The migration |
|---|---|
| the corpus layer — the fact is in no document, and the probe finds it only in the prompt | the document was never written for this fact, so this is a migration that did not happen rather than a test failure — back to step 1. `corpus-retrieval-tests` hands that verdict back to this skill by name |
| the document is at fault — chunk boundary or vocabulary | back to step 1. The block stays in the prompt meanwhile and the branch does not merge half-done |
| the retrieval layer is at fault — threshold or router — **and a signal for this class of turn exists**, so the layer had something to route on and got it wrong | **stop migrating** and fix the layer first (`retrieval-that-earns-its-place`). Every further block loaded onto a layer that cannot route multiplies one defect |
| the acceptance row was wrong | fix the row, re-run, **and record the edit in the ledger** — a row edited until it passed is honest only when the edit is visible |
| the router declined and **no signal for this class of turn exists** — nothing in the turn distinguishes the ones that need this block from the ones that do not | return the block to the **stays** side of the test above and record why. This is a result, not a failure: question 2 answered "yes" on paper and "no" in practice. The layer is not at fault and there is nothing to fix, which is what separates this row from the one above |

## The plan, and the committed ledger

Ranked, and **capped at ten blocks per run** — ten migrations that get done beat
thirty that get read. The rest stay in the inventory table, where the next run
finds them.

Two axes rank it — one out of the inventory's own columns, one out of git, and
neither out of taste:

- **Waste** — `B × C × (1 − U)` from the cost section. A block with `U unknown`
  ranks by `B` alone and carries the unknown into the ledger.
- **Volatility** — how often the content goes out of date. Its observable is the
  origin file's git history: how long since the block last changed, set against how
  often its subject changed somewhere else in that window. The plan entry in
  `MIGRATION-LEDGER.md` reads it out loud — "pricing changed twice since the block
  was written, and neither change reached this file". High volatility outranks high
  waste: a big stale block asserts wrong answers, a big fresh one only costs money.

Inside a rank, **silent before loud** — the block whose staleness produces a
confident wrong answer before the one that produces an obvious one. That tie-break
and the ranking procedure are `agentic-codebase-audit`'s; the axes differ because
the noun does.

The deliverable is one committed file at `docs/prompt-corpus-migration.md`, four
blocks in this order: the stamp lines and the six retrieval settings read this run,
the inventory table, the closed section, the capped plan. Same path every run and
committed, because a migration compounds only if the next run can `diff` against
the last. Where the repository already has a home for such documents, put the file
there and **commit a one-line pointer at the default path** — run 2 opens that path
and does not go looking, so a path recorded only in a commit message is a pointer
with no reader.

→ [`MIGRATION-LEDGER.md`](MIGRATION-LEDGER.md) — the four blocks worked against an
invented codebase, and the note on which shapes come from `agentic-codebase-audit`'s
`OUTPUT-SHAPES.md`. Open it before writing the first row.

## Running it again

**Read the ledger before you read the codebase**, and read the audit's prompt
assembly row next if there is one. Nothing at the ledger's path means this is run
1 and every **Previous** field reads `first run`.

**The settings block is the first thing you read and the first thing you compare.**
Read the six out of the codebase again and set them beside the recorded ones before
touching the inventory. A setting that moved changes what the already-closed
documents retrieve — a new segment size re-cuts every one of them — so the closed
section's acceptance suites re-run **before** a new block is ranked. That re-run is
`corpus-retrieval-tests`, which a model cannot load and this skill cannot execute:
ask the user to run corpus-retrieval-tests, which they invoke by name, and rank
nothing until its result is back. A run that
spends its cap on ten new migrations while the corpus it already owns retrieves
worse than it did last quarter is a net loss the ledger could have prevented.

**"Already migrated" is a row in the closed section**, carrying the date, the
document the block became, and the test that proves it. Closed rows are never
deleted: deleting them is how a fact walks back into the prompt with nobody able
to say it had ever left.

**The regression this pass exists to catch is new domain prose landing back in the
prompt**, because the prompt is the easy place to put it — no ingestion, no test,
no review of a document nobody owns. Detect it by diffing this run's inventory
against the last: a block that is new is either genuinely new content or a
re-import, and the closed section's subjects tell you which before you rank it.

### The check runs in both directions

`scripts/check-tool-catalogue.py` in this repository is the precedent, and its own
header says why the reverse direction earns its keep: a key in code and absent from
configuration "compiles, passes every unit test, and fails only when a user asks
the question that reaches it", while "a configured host nothing calls is an
allow-listed destination with no reason to be reachable". A corpus has both
failures:

- **prompt → corpus.** A fact present in both is the duplication above. Grep each
  closed row's subject against a freshly captured payload; a hit is a migration
  that did not finish.
- **corpus → prompt, the direction that is easy to skip.** A document the migration
  wrote that nothing routes to — embedded, correct, never retrieved, invisible
  because no test asks for it. **Every document in the corpus must be the intended
  chunk of at least one acceptance row** — `expected_source` in the query set. That
  is the same assertion `corpus-retrieval-tests` states as its document → row
  check, and the check itself is owned there, not specified a second time here: ask
  the user to run corpus-retrieval-tests, which they invoke by name, and record the
  result in the ledger.

## Not this skill's ground

| The question | Whose |
|---|---|
| whether to retrieve at all, what a corpus is for, the threshold measurement, the router's signal, whether retrieved text is written into chat memory | `retrieval-that-earns-its-place` |
| a control that is **absent** at a seam — no output guardrail, no tenant key in a conversation store, a catalogue that loads eleven of twelve entries | `agentic-codebase-audit` — a model cannot load it either; ask the user to run agentic-codebase-audit, which they invoke by name |
| how the splitter cuts, what makes a chunk survive alone, how a document is worded so the user's phrasing matches it | `writing-retrievable-knowledge` — the one skill in this chain a model may load itself |
| the query set's schema, what a positive and a negative row are, where the question wording comes from, and which of the four layers failed when the row is right and the corpus is wrong | `corpus-retrieval-tests` — ask the user to run corpus-retrieval-tests, which they invoke by name |

## Done when

**Rows 1–7 and 13–14 run once per audit. Rows 8–12 repeat per block**, once for
each entry on the capped plan that row 7 produces; a run that migrated three blocks
answers them three times, and a run whose plan was all `stays` answers them none.

Three rows below — 6, 10 and 13 — complete only when a skill this one cannot invoke
has run. `corpus-retrieval-tests` carries `disable-model-invocation: true`, so a
model working through this table cannot start it and must not report those rows as
done on its own: **ask the user to run corpus-retrieval-tests, which they invoke by
name**, and carry its result into the row.

| # | Check | Done when |
|---|---|---|
| 1 | Open the previous ledger, then the audit's prompt assembly row if there is one | every **Previous** field carries a verdict from the last run, or `first run`; a second assembly point named by the audit is in the inventory |
| 2 | Capture one payload and grep a sentence back | one ordinary turn's exact payload is on disk, and a distinctive sentence resolves to the file that emitted it |
| 3 | Inventory the standing text | every block in the payload carries a `file:symbol` origin and a byte count; any remainder is named as a dependency, a default or the stored conversation — never left blank |
| 4 | Cost each block | each carries `B` and either a defensible `U` or the literal `U unknown` — never a share nobody measured. `C` is definitional and is not a per-row field |
| 5 | Run S, D and the three questions | each block reads one of the four closed verdicts — `stays`, `moves`, `split`, `neither` — naming the check that decided it (`S`, `D`, `Q1`, `Q2`, `Q3`); no `split` row reaches the plan unsplit; every arguable `Q2` resolved to `stays` |
| 6 | Read the retrieval configuration | all six settings in the ledger's settings block, each numeric value with its unit, the file it came from — the library's documentation and lock-file version for any it defaults and nobody set — and the previous run's value or `first run`. Any setting that moved has the closed section's acceptance suites re-run before a new block is ranked, and that re-run is `corpus-retrieval-tests`: the row is done when the user, asked to run corpus-retrieval-tests by name, has come back with its result — never on a model's own say-so |
| 7 | Rank and cap the plan | row 6 came back first — where a setting moved, the closed section's re-run was green before anything here was ranked. Then every `moves` block, and every child of a `split`, never the parent, is ranked on waste and volatility, tie-broken silent before loud, and the plan carries **at most ten** entries; each entry names its verdict, its evidence and its smallest change; every block that did not make the cap is still in the inventory table, where the next run finds it. Rows 8–12 below run once per entry on this plan, so a plan that does not exist is a run that stops here |
| 8 | Write the acceptance list | committed, in users' words, **before** the document exists, as rows in the one query set beside the corpus — no second list at a path of this skill's own |
| 9 | Write the document | `writing-retrievable-knowledge` ran, and every acceptance question has a named intended chunk — `expected_source` in the query set |
| 10 | Prove it | ask the user to run corpus-retrieval-tests, which they invoke by name, and the answer that comes back is green: every question retrieves its `expected_source` through the shipped router at the shipped threshold, and any edited row's edit is recorded |
| 11 | Delete the block | one commit, that block only |
| 12 | Re-measure | a **freshly captured** payload no longer contains the block, and the acceptance questions still answer correctly end to end |
| 13 | Check both directions | no closed row's subject appears in the fresh payload; the corpus → prompt direction is `corpus-retrieval-tests`' document → row check — ask the user to run corpus-retrieval-tests, which they invoke by name, and record here that every corpus document is some acceptance row's `expected_source` |
| 14 | Commit the ledger | settings block, inventory, closed section and capped plan, stamped with this run's date, branch and commit and the previous run's, at the stable path or behind a one-line stub there |

A block that returned to **stays** is a completed migration, not an abandoned one,
provided the ledger says which question turned it back. The entry that ends a
migration is the one nobody can reconstruct next quarter: a block deleted from the
prompt with no acceptance list behind it, and no way left to tell whether the
answer it used to shape is still being shaped.
