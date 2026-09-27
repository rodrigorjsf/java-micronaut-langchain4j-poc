---
name: agentic-evals
description: Use when writing or reviewing evals, when a prompt, a tool description or a rule changes and someone has to show nothing regressed, when building a labelled dataset for a guardrail or classifier, when deciding what a build gates on versus what it only reports, when choosing how many rows a gate needs and where its threshold goes, when the same suite scores differently on two runs, when an LLM judge is scoring another model's output, or when a row fails and the row may be the thing that is wrong. For driving work toward a bar rather than measuring the judge that grades it, use gauntlet-loop.
---

# Evals that gate a change

A prompt is code with no compiler. Reword one line of it and you can lose a
refusal, flip a routing decision or drop a language, and every existing test
still passes because none of them look at behaviour. An **eval** is the missing
test: a labelled dataset, a score, and a threshold that fails the build.

One split organises the suite: **does this need a model to run?** A
**deterministic** suite — no model call, no key, no network — gates every commit;
a **live** one runs before a release. A suite needing a key and, illustratively, four
minutes on every push gets marked ignored by the third person it blocks.

| You are here because | Start at |
|---|---|
| a prompt, a tool description or a rule changed and nothing may regress | *The deterministic suite* |
| you are building a labelled dataset for a guardrail or classifier | *Building the near-miss half*, then [`NEAR-MISS-HALF.md`](NEAR-MISS-HALF.md) |
| you are choosing how many rows a gate needs and where its threshold goes | [`SIZING.md`](SIZING.md), then *Asymmetric gates* |
| the suite needs a real model, or scores differently on two runs | [`GOLDEN-SET.md`](GOLDEN-SET.md), then [`RUNNING-A-SUITE.md`](RUNNING-A-SUITE.md) |
| an LLM judge is scoring another model's output | [`JUDGE-CALIBRATION.md`](JUDGE-CALIBRATION.md) |
| deciding what the build gates on versus what it only reports | *Asymmetric gates*, then *Drift and skipped* |
| a row fails and the row may be the thing that is wrong | *When the row is the thing that is wrong* |
| an existing suite is in front of you | *Reviewing an eval suite* |

## The deterministic suite

This is the one that catches the regression that actually happens: a rule
tightened to fix one complaint quietly loses three of the things it was built to
catch. Arriving with a change, find your deterministic asset first:

| What changed | The deterministic asset | What only a model can answer |
|---|---|---|
| a detector or classifier rule | labelled rows, scored | agreement with a human on rows people argue about |
| a tool description | a snapshot of the shipped text: any edit to it fails the build until someone re-runs routing and updates the snapshot | whether the model still picks it for the right request |
| a refusal or a template | the promises your refusals make, over every variant | whether it reads as helpful |

They reach well past detectors — these are ordinary tests that are really evals:

| Deterministic assertion | The regression it catches |
|---|---|
| every refusal offers an alternative and stays under a character cap | a prompt edit that turns refusals into lectures |
| compaction preserves the state that gates behaviour — the invariant, owned by `conversation-memory-and-compaction` | a summarised-away capability marker, and the agent silently loses a tool nobody removed — **ASI06 Memory & Context Poisoning** with no attacker in it. The invariant is the control; the assertion is why it survives the next edit to the summariser |
| the standing prompt is byte-identical across turns — the cache floor, owned by `agentic-service-composition` | an interpolated timestamp that ends every prompt-cache hit |
| every few-shot example in the prompt is one the component's own stated rules label the same way | a policy edit that rewrites the rules and leaves the examples teaching the rule they replaced — and a model follows the examples |

**A failing gate names the row, not only the number**, because `expected >= 0.90,
got 0.87` costs a re-run with logging turned on before anyone can start work.

### Assert the path, not only the answer

An agent can reach a right-looking answer the wrong way — from memory instead of
the tool, after a forbidden tool, or on the fourth retry. A **trajectory
assertion** checks how the turn got there. The check itself is deterministic;
the turn it reads is scripted (a stub model, in this suite) or live (a scenario
run against the real model, in the live suite below). In LangChain4j an AI
Service method that returns `Result<T>` exposes `toolExecutions()`: one
`ToolExecution` per call, with `request().name()`, `request().arguments()`,
`result()` and `hasFailed()`. A guardrail's verdict is asserted the same way
through `GuardrailAssertions.assertThat(result)` from the `langchain4j-test`
module — `.hasResult(GuardrailResult.Result.FATAL)`,
`.hasSingleFailureWithMessage(...)`, and for an output guardrail
`.hasSingleFailureWithMessageAndReprompt(...)` — with no model call at all.

| Trajectory assertion | The regression it catches |
|---|---|
| the expected tool ran, with the argument the request named | an answer invented from training data, correct today and wrong tomorrow |
| a forbidden tool never ran | a description edit that pulled a side-effecting tool into a read-only request |
| tool calls stay under a bound, and none `hasFailed()` | a model looping on one tool until the step limit ends the turn |
| the guardrail fails this input `FATAL`, with the message the user is meant to see | a rule edit that stopped matching the request it was written for, or demoted a stop (`FATAL`) to a `FAILURE` that lets the remaining guardrails run |

[sourced, read 2026-09-27 — `Result` and `ToolExecution` in
`langchain4j/src/main/java/dev/langchain4j/service/` and `GuardrailAssertions` in
`langchain4j-test`, all at tag 1.20.1; usage from
docs.langchain4j.dev/tutorials/guardrails]

## Building the near-miss half

The dataset is a file in the repository with **rows in a stable order — never
regenerated wholesale by a script, never shuffled**, because several rules below
are enforced by reading a diff. A row is `{id, families, label, text, reason}`: a
stable `id`, so a failure names a row you can open; `families`, the tags an
assertion selects on — mechanism and provenance — so a failure names what to look
at; and `reason`, written the day the row is created and the only defence you have
the day it fails.

**What may be generated is the input, never the label.** A model may draft the
`text` of a row — useful before real traffic exists, or to cover a combination
nobody has typed yet — provided a person reads every drafted row before it is
committed, and the row carries a `synthetic` provenance family so a failure shows
where it came from. Those are **human-audited synthetic inputs**. The `label` and
the `reason` are never drafted by a model: a label a model wrote measures
agreement with that model, and when it is the model under test the row passes by
construction ([`GOLDEN-SET.md`](GOLDEN-SET.md) makes the same point about labellers).

```
{ id: "near-miss-014", families: ["delete-verb", "complaint"], label: "benign",
  text: "what happens to my data if I delete my account?",
  reason: "reported false positive, 2025-03 — the verb is in a question about
           consequences, not in a request to act" }
```

The positive half writes itself; the **near-miss half** — rows the component must
label benign, each built to look like the thing being caught — is where the work is,
and it is not padded. → [`NEAR-MISS-HALF.md`](NEAR-MISS-HALF.md): deriving the
candidates from your own rules and prompt clauses, minimal pairs, the count read off
the gate, the `evasion` and `complaint` families asserted row by row, the three-row
first pass for a change that ships today, and when the dataset is finished.

## The live suite: the golden set

A **live eval** runs the real component against a real model on labelled rows,
on demand and before a release, never in the commit gate. It is built around the
boundary, its failure modes come from error analysis on real traces, every gated
row is labelled twice and no gate sits above the labellers' agreement, and one run
is one sample → [`GOLDEN-SET.md`](GOLDEN-SET.md), with when the set is finished.

## A judge is an unevaluated classifier

When the scorer is itself a model — a judge, or a grader when it scores a rubric —
it carries every defect you are gating against, unmeasured. Before one of its
verdicts gates anything, it gets its own human-labelled set, sized per failure
mode with both classes, scored as TPR and TNR separately, and recalibrated when
the grader, its prompt or the model under test changes →
[`JUDGE-CALIBRATION.md`](JUDGE-CALIBRATION.md).

## Asymmetric gates: two numbers, two denominators

When one error costs more than the other, one number cannot gate the change: an
average lets the expensive error grow as long as the cheap one shrinks to match.

Worked, on 62 rows — 40 that should be served, 22 that should not:

```
5 errors, all of them refusing a request that should have been served
  accuracy       57/62 = 0.919   passes a >= 0.90 gate
  false refusal   5/40 = 0.125   blows a <= 0.05 gate
```

**The denominators differ, and that is the point.** Accuracy divides by every row
that ran; the false-refusal rate divides only by rows that should have been
served. Divide both by 62 and the second becomes 0.08 — the same average wearing
a different name.

Which error is expensive is a product question, and one rule settles most cases:
**an error the user corrects on the next turn is cheap; an error they cannot see,
or cannot undo, is expensive.**

| Component | Cheap error | Expensive error |
|---|---|---|
| retrieval router | retrieving when it was not needed — tokens | answering confidently without the document that had the answer |
| confirmation before a destructive tool | one extra confirmation | an irreversible action nobody approved |
| an extractor filling a record from a message | one clarifying question you did not need | a wrong value written where nobody re-reads it |

**Every threshold constant carries three things** — the expensive error it is named
after, how many failures it actually permits, and the smallest change the suite can
resolve. `MAX_FALSE_REFUSAL_RATE = 0.05`, annotated *a real user turned away; 2 of
the 40 servable rows; blind to anything smaller than ±6.8 points*, is a number
someone argues about before lowering it. A bare `0.05` is not. Both of the last two
figures come off the denominator the rate divides by — the 40 servable rows — never
off the 62 in the file, and the third is computed from `SIZING.md`'s formula **at
the rate this constant gates**: `1.96 · sqrt(0.05 · 0.95 / 40)` = ±6.8 points. Do
not copy it out of that file's table, whose cells are the `p = 0.90` case and print
±9.3 for these same 40 rows — a third wider than the gate's real blind spot.

## Drift and skipped: two things a run reports without failing on

A build going red for something that is not a regression teaches the team to
ignore red, which costs you the gate itself. Two cases earn a report instead.

**Labels that moved are drift: print them, do not gate them.** Gate the outputs
that change what the user gets; a secondary label, a confidence band or a chosen
template moves on every model version for reasons unrelated to your change. Drift
is still a regression waiting for a consumer — a decision that stayed right while
its label moved breaks whatever keys on that label. Promote a pair into the gate,
and out of the drift report, the moment a metric dimension, a template lookup or a
routing hint reads it. **The drift report is also where individual rows go** —
a row two labellers read differently tags out of the gate the same way an
`evasion` row tags into one, and lands in the same advisory bucket as the
dimensions above. The printed per-dimension block is not all of it.

**A skipped row is not a failure.** A quota error, a provider 5xx and a timeout
are not wrong answers. Count them **skipped**, exclude them from both denominators,
and report passed, failed and skipped as three separate counts — one merged
"58 failures" line cannot tell an outage from a regression. Scoring an
infrastructure error as a classification error breaks the number in the most
expensive direction: it looks like a quality regression, so someone spends an
afternoon changing a prompt that was fine.

**Then fail the run if too few rows ran** — half the rows is a defensible floor.
Without it the skip rule degenerates into its own worst failure: every row
rate-limited means zero rows ran, and accuracy over zero rows offends no
threshold.

## When the row is the thing that is wrong

Sometimes the component is right and the label was wrong. This is the moment the
suite is most likely to be quietly destroyed, so it gets a bar.

**Change a row only when you can state the rule it now violates without
mentioning the build.** The discriminator: would you have labelled it this way
*before* seeing the failure? If you cannot answer without reading the diff, the
row stands and the change under test is the thing that is wrong.

**When the row was genuinely ambiguous, splitting beats editing** — two rows that
are each unarguable, or one moved to the advisory drift set. A row two people read
differently measures the reader.

**Change the row in its own commit,** with the reason in the message. A golden set
edited quietly to make a build green still prints a number, and the number no
longer means anything.

**When the gate fails and the change ships anyway, override it by name** rather
than lowering the constant until the run is green.

[`RUNNING-A-SUITE.md`](RUNNING-A-SUITE.md) has the mechanics these rules need on
the day: pacing a live run under a measured rate limit, what the `ran / total` and
drift blocks print, and the four fields an override records.

## Reviewing an eval suite

Ask, in order:

1. How many rows ran on the last green run? A suite that passed on 3 of 62 rows
   is the failure that looks most like success.
2. What is the denominator of each rate, and how many failures does its threshold
   permit on that denominator? Do the arithmetic: a rate that permits none is a
   zero-failure count mislabelled as a tolerance, and one that permits five was
   probably not meant to.
3. Which gate is satisfied by doing more of the thing you are trying to limit? A
   number maximised by refusing everything needs a second with a different
   denominator.
4. Where did the labels come from, and do any of the rows also appear in the
   prompt?
5. If a model scores the output, who scored the scorer? A judge with no
   human-labelled set of its own — at least 60 rows per failure mode, both classes,
   TPR and TNR reported separately, re-measured since the grader, its prompt or the
   model under test last changed — or one grading on an absolute scale rather than
   pairwise against a baseline, makes every number downstream of it decorative.
6. Does every edited label in the dataset's history carry a reason, in its own
   commit?
