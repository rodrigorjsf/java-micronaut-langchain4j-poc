---
name: agentic-scenario-suite
description: Run when a system prompt, tool description, tool schema, skill, sub-agent, catalogue entry or guardrail changed and you need the scenarios it puts at risk; when a new tool, skill or domain has no scenarios; or when an agentic backend needs its first end-to-end scenario dataset.
disable-model-invocation: true
---

# Scenarios for every domain an agent serves

A developer rewords a tool description and every unit test stays green, because
none of them sends a user sentence through the whole turn. A **scenario** does:
user text in, triage, guardrails, activation, tool calls, answer out, checked
against what the artifacts say must happen. This skill builds and maintains the
committed scenario dataset. It is a **ledger** of the agent's promises: every
artifact that can change an answer is on it, every domain has its floor of rows,
and every row names the artifacts it depends on, so a change to one of them names
the rows to revisit.

| You are here because | Start at |
|---|---|
| there is no dataset yet | step 1, and read every step in order |
| a tool, skill or domain was added and has no rows | step 1 for the new artifact, then steps 4–6 |
| artifacts changed since a ref and you need the rows at risk | *Diff mode* |
| you are writing or reviewing a row | [`SCENARIO-SCHEMA.md`](SCENARIO-SCHEMA.md) — fields, kinds, the floor, where each expectation comes from |
| you are finding artifacts, domains or real user sentences | [`INVENTORY.md`](INVENTORY.md) — any backend, with skills or without |
| a run finished and you are reading `eval-report.html` | [`READING-A-RUN.md`](READING-A-RUN.md) |
| you are working in the repository this skill was written in | [`WORKED-EXAMPLE.md`](WORKED-EXAMPLE.md) — paths, identifiers, commands, the committed rows |
| you need row counts, thresholds, repetitions or a calibrated grader | `agentic-evals` — that skill owns measurement |
| the questions are about what a corpus retrieves, not what the agent answers | `corpus-retrieval-tests` — ask the user to run it; they invoke it by name |

## Four rules that hold on every path

1. **Only user inputs may be drafted by a model.** A model may propose the
   sentences a user types. It never writes an expectation: the expected outcome,
   tool, argument, answer content and rubric are read off the artifacts —
   the tool's description, the skill body, the catalogue, the guardrail's rule.
   An expectation drafted by the model under test measures the model against
   itself and passes by construction.
2. **Every generated or changed row reaches a human as a diff before it is
   committed.** Show the diff of the dataset files and stop. The reviewer accepts,
   edits or rejects each row; nothing you drafted is committed on your own
   judgement.
3. **Every row records its `source`**: `trace` (paraphrased from real traffic),
   `user` (written by a person), or `synthetic` (drafted by a model). The report
   shows it, so a reader can weigh a failure on a synthetic row differently from
   one on a sentence users really type.
4. **Complement, never overwrite.** Existing rows are curated. Add rows beside
   them; a row that needs changing is proposed as its own hunk with a one-line
   reason, and its `id` stays the same so its history stays readable.

## Building or extending the dataset

**Step 1 — Inventory the artifacts.** List everything that can change what the
model answers, in the six kinds [`INVENTORY.md`](INVENTORY.md) names: system
prompt sections, tools (description and schema), skills, sub-agents, catalogue
entries, guardrails. Give each the identifier a row's `dependsOn` will use.
*Done when* every artifact in the codebase appears once with its identifier, and
you can say for each kind where you looked.

**Step 2 — Derive the scenario domains.** A **domain** is a flow a user can reach
through the agent — weather lookup, company lookup, trip briefing — named in the
words users ask in. It may be a skill's territory, a bare tool or a sub-agent; it
is never defined as "a skill", because many backends have none. Mark which
domains hold state across turns. *Done when* every tool and sub-agent belongs to
at least one domain, and each domain has a one-sentence description a user would
recognise.

**Step 3 — Source the inputs.** Before drafting a sentence, ask the user to
export real traffic for each domain — from Langfuse or any other observability
platform, application logs, or support reports — and read it for the words users
actually type. Every sentence taken from real traffic is **paraphrased and
anonymised** before it enters the dataset, following [`INVENTORY.md`](INVENTORY.md).
When no export exists, say so and draft synthetic inputs. *Done when* each domain
has candidate inputs, each tagged with the `source` it will carry, and no
committed candidate contains personal data.

**Step 4 — Draft rows up to the coverage floor.** Per domain: at least **2 happy
paths**, at least **3 bad paths** from the named set, and **1 multi-turn** row
when the domain holds state; add cross-domain rows for turns users combine.
Derive each expectation from the artifact it names, using the table in
[`SCENARIO-SCHEMA.md`](SCENARIO-SCHEMA.md). *Done when* every domain meets the
floor, every row's `dependsOn` names only identifiers from step 1, and every
expectation can be traced to an artifact sentence you can quote.

**Step 5 — Complement the existing rows.** Load the committed dataset first.
Keep every row; add yours beside them in stable order; mark a row whose
expectation now contradicts an artifact as *to adjust*, with the artifact sentence
that contradicts it. *Done when* no existing row was removed or silently
rewritten.

**Step 6 — Present the diff and stop.** Show the dataset diff, a table of rows
added and rows to adjust, and the domains still below the floor. *Done when* the
user has reviewed it; commit only what they accepted.

**Step 7 — Run and read.** The user runs the suite; you read the report with
[`READING-A-RUN.md`](READING-A-RUN.md). A failing row is diagnosed before it is edited: the
row, the artifact or the agent can each be the thing that is wrong.

## Diff mode

Given a git ref, list the artifacts whose content changed since it (step 1's
inventory at the ref against the working tree), then report three lists:

| List | What it holds | What the user does with it |
|---|---|---|
| **rows at risk** | every row whose `dependsOn` names a changed artifact | run them; for an intended behaviour change, adjust the ones whose expectation the change falsifies |
| **rows to adjust** | rows at risk whose expectation now contradicts the changed artifact, each with the sentence that contradicts it | review the proposed hunk |
| **uncovered** | artifacts no row depends on, new ones first | draft rows for them through steps 3–6 |

A fingerprint may be coarser than the artifact, never finer: selecting too many
rows costs one run, selecting too few lets a change ship untested. *Done when*
every changed artifact appears in one of the three lists.

## Where this skill stops

Measurement belongs to `agentic-evals`: how many rows a gate needs, where its
threshold sits, how many repetitions make a pass rate honest, and how a model
grader is calibrated (true-positive and true-negative rates, measured separately)
before its verdicts may gate anything. This skill writes the rows and reads those
rules off that skill rather than restating them. Until a grader is calibrated,
a rubric is reported, never counted.
