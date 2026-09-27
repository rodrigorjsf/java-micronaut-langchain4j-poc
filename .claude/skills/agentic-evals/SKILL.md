---
name: agentic-evals
description: Prove a prompt, classifier or guardrail change safe before it ships. Use when a prompt, a tool description or a rule changes and someone has to show nothing regressed, when building a labelled dataset for a guardrail or classifier, when deciding what a build gates on versus what it only reports, when choosing how many rows a gate needs and where its threshold goes, when the same suite scores differently on two runs, when an LLM judge is scoring another model's output, or when a row fails and the row may be the thing that is wrong. For driving work toward a bar rather than measuring the judge that grades it, use gauntlet-loop.
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
construction (the golden set below makes the same point about labellers).

```
{ id: "near-miss-014", families: ["delete-verb", "complaint"], label: "benign",
  text: "what happens to my data if I delete my account?",
  reason: "reported false positive, 2025-03 — the verb is in a question about
           consequences, not in a request to act" }
```

The positive half writes itself. The **near-miss half** is where the work is: rows
the component must label benign, each built to look like the thing being caught.

**Derive the candidates from your own rules, then pair each one.** Walk the list
of patterns, keywords and thresholds the component fires on, and for each write
the sentence a real user would send that contains it. **A prompt has that list
too, and it is the promises the prompt makes:** every *always X* and *never Y*
clause is a rule, and its near-miss is the request that looks like it fires the
clause and must not. Either way the walk is mechanical and has a definite end.

```
BAD    near-misses written as "normal traffic"
       "hi"   "thanks!"   "where is my order"

GOOD   walking a destructive-intent detector's list (delete/cancel/reset/remove)
       positive   "delete my account"
       near-miss  "what happens to my data if I delete my account?"
       positive   "cancel everything on this account"
       near-miss  "how do I cancel my subscription at the end of the term?"

GOOD   walking a prompt's clauses — here "never give dosage advice"
       positive   "how many mg of this should I take?"
       near-miss  "what dose does the label on the box say?"
       near-miss  "why did my prescription change between refills?"
```

Keep each one beside the positive it neighbours. A **minimal pair** — same words,
different intent — tells you which clause your rule keys on; a positive lifted
from a security write-up and a near-miss lifted from a product FAQ differ in a
hundred ways, and a failure on either tells you nothing.

For an injection detector this half is the false-positive control that
`prompt-injection-layers` owns; that skill owns the control and what a misfire
costs, this one owns how the rows proving it are derived, paired and counted.

**Every false positive anyone reports becomes a permanent row, before the fix
lands** — the dataset is a regression log, not a sample of traffic, and a
complaint fixed without a row comes back.

**Choose the near-miss count before writing the positives.** A rate can only take
the values its denominator allows, so the count is read off the gate you intend to
set, never discovered afterwards — and it fixes how small a regression the suite
can see at all. Before choosing one, read [`SIZING.md`](SIZING.md): reachable gate
values, and the band around the score. **The rule walk sets the floor, the gate
sets the target** — nine rules yield nine near-misses, and a gate needing more is
owed the difference in reported false positives plus minimal pairs on the rules
that misfire most. A component with too few rules to reach the count is not padded
up to it: under roughly twenty near-miss rows the gate is a count, not a rate, and
is written as one. Below twenty rows the count `<= 0.05` implies is **no false
positives at all** — 1/19 = 0.053 fails it, and one permitted miss first becomes
reachable at exactly `n = 20`. That is the reason to write it as a count: the
percentage disguises a zero tolerance as a 5% allowance, and whoever restates the
gate as *at most one false positive* loosens it believing they copied it.

**Two families get their own assertion instead of being averaged in: every row
tagged with them passes, or the run fails.** An aggregate hides a regression by
design: any rate loose enough to be reachable absorbs the first miss silently, and
these are the two places you least want to spend that allowance. Both families
exist in any component:

- the **`evasion`** family — rows differing from one you already handle only by a
  transformation the component should be blind to: encoding or spacing for a
  detector, a misspelling or synonym for a retrieval query, a rephrasing for a
  classifier, and for a prompt clause the forbidden ask put as a hypothetical, as a
  third party's question, or in another language. One slip means it keys on surface
  form rather than on intent, or that a shared normalising step regressed — and the
  aggregate barely moves either way.
- the **`complaint`** family — every row from a real reported failure. Each has
  already cost somebody a support thread, so a regression there is a repeat, and
  the user reporting it a second time stops reporting.

For a guardrail, this dataset is what stops **Agent Goal Hijack (ASI01)**
reopening: the guardrail is the control, the dataset is what keeps it from being
narrowed away one reasonable-looking commit at a time.

**Rows generalise past detectors.** For a retrieval layer each row pairs a question
with the document that should come back — **including rows whose expected result is
nothing at all**; those expected-misses are its near-miss half, and without them the
layer answers unrelated questions while passing every test it has.
`retrieval-that-earns-its-place` owns where the threshold sits and whether it
separates at all; the rows that pin it there, so the next embedding model cannot
move it quietly, are this dataset.

**With no suite at all and a change to ship today, the first pass is three rows,
not a dataset:** the rule or prompt clause the change touches, one evasion row
against it, and one complaint row — or, before anyone has complained, the near-miss
that pairs that rule. Assert each by name in the file the change lives in. Three
rows that fail by name beat a sixty-row set next quarter.

**The dataset is finished when** every rule the component enumerates — pattern,
threshold, or promised prompt clause — has at least one near-miss row, every
positive has a paired near-miss, and every false positive anyone has reported has
a row. Short of that, the score measures the rows someone found easy to write.

## The live suite: the golden set

A **live eval** runs the real component against a real model on labelled rows. Tag
it to run on demand and before a release, and keep it out of the commit gate: it
needs a key, it costs money, it depends on someone else's rate limit.

**Build it around the boundary** — the request that is vague but servable, the
hostile message that also carries a genuine question, the plausible request
nothing you built serves. Straightforward rows catch a catastrophic regression
and nothing else.

**Find the failure modes by error analysis on real traces before writing rows for
them.** Read a sample of real conversations end to end — every trace from the test
inputs, and real user traces as soon as there are any — label each pass or fail,
and for every fail write one line on why. Group those lines by hand: each group is
a **failure mode** ("cites a figure no tool returned", "asks for the city it was
already given"). Each mode becomes a family in the dataset, the unit a grader's
calibration set is sized per (below), and — where a check can see it — a
deterministic assertion. Keep reading until new traces stop producing new modes. A
suite built from imagined failures measures the imagination; the traces show what
actually breaks.

**Ask where each label came from.** A label written by the author of the prompt
under test, or generated by the model under test, measures agreement with the
thing being tested, and the row passes by construction. Two labelling passes with
two different outputs answer this. **A sample labelled independently by two people
produces the agreement number**, and **no gate sits above the rate at which they
agreed** — a threshold above your own labelling agreement measures the labellers.
**A second labeller on a row is what makes that row gate-eligible**, so the gated
set is double-labelled in full and the sample is the measurement taken inside it.
The sample bounds the gate; the full pass admits the row.

**When they agreed on only 0.70 of the sample, the labels are the defect and the
gate is not where you absorb it.** Read the rows they split on — usually two rules
of the component's own policy collide there. Rewrite the rule until a third person
reproduces the labels and measure agreement again. A 0.70 gate over a set nobody
can label twice is a number that moves when the labellers do.

**A tool-description change is a live-suite change**, proved by a set of user turns
each labelled with the item that should fire: no code changes, so no functional
test can break. This skill owns how a dataset is built and sized; the
`reviewing-agent-tools-and-skills` skill owns that particular set and the
before/after run it feeds. What follows applies to it as to any live set.

**One run is one sample — here, not in the deterministic suite.** That one
reproduces its own number exactly; a live one does not, so a gate set at the
number you measured once will flap. Measure **five runs**, set the gate below the
worst, and treat *re-running until green* exactly as you treat editing a row
until green. If the spread across five runs is wider than the regression you want
to catch, the gate cannot see it at all.

**The golden set is finished when** all three boundary classes above have rows;
every row records who labelled it, never the author of the prompt under test, and
a second labeller reproduced that label; every release-blocking failure has a row;
and no row also appears among the prompt's few-shot examples — diff the two files,
the intersection is empty.

## A judge is an unevaluated classifier

When the scorer is itself a model — grading an answer for helpfulness,
faithfulness or tone — it carries every defect you are gating against, and none of
them have been measured. (This page calls it a judge, or a grader when it scores a
rubric; the two words name the same thing.) Before one of its verdicts gates anything, give it its
own human-labelled set, and label it exactly as the golden set above: two
labellers, agreement ceiling and low-agreement remedy included.

**Size it per failure mode, with both classes: at least 60 rows per failure mode,
about 100 when you can**, and each failure mode holds rows a human labelled *fail*
and rows a human labelled *pass*. Below 60 the band around each rate is too wide
to conclude anything — [`SIZING.md`](SIZING.md) has the arithmetic.

**Score it as two rates, not one agreement number.** With the human labels as
ground truth, the **true positive rate** (TPR) is the share of human-*fail* rows
the grader also fails, and the **true negative rate** (TNR) the share of
human-*pass* rows it also passes. Each divides by its own class — the asymmetric
gates below, applied to the grader. Raw agreement hides the difference: on a set
that is 90% passes, a grader that passes everything agrees 0.90 of the time and
catches no failure at all.

```
BAD    grader agreement 55/60 = 0.92 (54 pass rows, 6 fail rows)
       passes all 54, fails 1 of the 6 failures — TPR 1/6 = 0.17

GOOD   60 rows for "cites a figure no tool returned": 30 fail, 30 pass
       TPR 27/30 = 0.90   TNR 28/30 = 0.93   each reported, each gated
```

**Recalibrate when anything the verdict depends on changes** — the grader model,
the grader's prompt, or the model under test. Each shifts the outputs the grader
sees or the way it reads them, so the TPR and TNR measured before describe a
different instrument. Re-run the calibration set and re-read both rates before
the grader's next verdict gates.

[sourced, read 2026-09-27 — the LangChain4j *Testing and Evaluation* tutorial,
docs.langchain4j.dev/tutorials/testing-and-evaluation (raw
`docs/docs/tutorials/testing-and-evaluation.md`), and the two posts it lists
first: Hamel Husain, *Creating a LLM-as-a-Judge That Drives Business Results*,
https://hamel.dev/blog/posts/llm-judge/ (modified 2026-09-01) — per-failure-mode
sizing ("about 100 examples per failure mode, with enough Pass and Fail examples
to measure both classes. Below 60 examples, the confidence intervals are often
too wide to support a useful conclusion"), TPR and TNR over raw agreement
("report the judge's True Positive Rate and True Negative Rate separately"),
re-running the review "whenever something material changes", synthetic data
limited to user inputs, and error analysis; and *Your AI Product Needs Evals*,
https://hamel.dev/blog/posts/evals/ — reading traces, and LLM-drafted test
inputs.]

Then ask it a question it can answer the same way twice.

```
BAD    "rate this answer 1-5 for helpfulness"
       one unchanged answer scores 4, 3, 4 across three runs
       the >= 3.5 gate flaps, and nobody can say what 3.5 means

GOOD   "which answer is better?" — candidate against a fixed baseline answer
       ask each pair twice: baseline first, then candidate first
       count a win only when the same answer wins both orders
       an order-flip is a no-win, not a discarded row
```

An absolute score is a scale the judge re-invents every run; a pairwise verdict
compares against something fixed. Both orders removes position bias and most of
the judge's preference for length; counting the flip keeps the denominator honest.

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
