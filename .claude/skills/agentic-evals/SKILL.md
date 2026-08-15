---
name: agentic-evals
description: Prove a prompt, classifier or guardrail change safe before it ships. Use when a prompt changes and someone has to show nothing regressed, when building a labelled dataset for a guardrail or classifier, when deciding what a build gates on versus what it only reports, when an LLM judge is scoring another model's output, or when a case fails and the case may be the thing that is wrong.
---

# Evals that gate a change

A prompt is code with no compiler. Reword one line of it and you can lose a
refusal, flip a routing decision or drop a language, and every existing test
still passes because none of them look at behaviour. An **eval** is the missing
test: a labelled dataset, a score, and a threshold that fails the build.

One split organises the suite: **does this need a model to run?** What does not
gates every commit; what does runs before a release. A suite needing a key and
four minutes on every push gets marked ignored by the third person it blocks.

## Level 1 — the half that needs no model

A **level-1 eval** is assertions over a labelled dataset, with no model call and
no network. It catches the regression that actually happens: a rule tightened to
fix one complaint quietly loses three of the things it was built to catch.

Arriving with a change, find your level-1 asset first:

| What changed | The level-1 asset | What only a model can answer |
|---|---|---|
| a detector or classifier rule | labelled rows, scored | agreement with a human on rows people argue about |
| a tool description | a property test over the text | whether the model still picks it for the right request |
| a refusal or a template | the invariants your refusals promise, over every variant | whether it reads as helpful |

Level 1 reaches well past detectors. These are ordinary tests that are really
evals:

| Deterministic assertion | The regression it catches |
|---|---|
| every refusal offers an alternative and stays under a character cap | a prompt edit that turns refusals into lectures |
| compaction preserves the state that gates behaviour | a summarised-away capability marker, and the agent silently loses a tool nobody removed |
| the standing prompt is byte-identical across turns | an interpolated timestamp that ends every prompt-cache hit |
| every few-shot example in the prompt is one the component's own stated rules label the same way | a policy edit that rewrites the rules and leaves the examples teaching the rule they replaced — and a model follows the examples |

**A failing gate names the row, not only the number**, because `expected >= 0.90,
got 0.87` costs a re-run with logging turned on before anyone can start work.

## Building the near-miss half

The dataset is a file in the repository with **rows in a stable order — never
generated, never shuffled**, because several rules below are enforced by reading a
diff. A row is `{id, family, label, reason, text}`: `id` stable, so a failure names
a row you can open; `family` grouping rows by the mechanism they exercise, so a
failure names what to look at; `reason` written when the row is created, and the
only defence you have the day the row fails.

```
{ id: "near-miss-014", family: "delete-verb", label: "benign",
  text: "what happens to my data if I delete my account?",
  reason: "reported false positive, 2025-03 — the verb is in a question about
           consequences, not in a request to act" }
```

The positive half writes itself. The **near-miss half** is where the work is: rows
the component must label benign, each built to look like the thing being caught.

**Derive the candidates from your own rules, then pair each one.** Walk the list
of patterns, keywords and thresholds the component fires on, and for each write
the sentence a real user would send that contains it. A mechanical walk with a
definite end.

```
BAD    near-misses written as "normal traffic"
       "hi"   "thanks!"   "where is my order"

GOOD   walking a destructive-intent detector's list (delete/cancel/reset/remove)
       positive   "delete my account"
       near-miss  "what happens to my data if I delete my account?"
       positive   "cancel everything on this account"
       near-miss  "how do I cancel my subscription at the end of the term?"
```

Keep each one beside the positive it neighbours. A **minimal pair** — same words,
different intent — tells you which clause your rule keys on; a positive lifted
from a security write-up and a near-miss lifted from a product FAQ differ in a
hundred ways, and a failure on either tells you nothing.

**Every false positive anyone reports becomes a permanent row, before the fix
lands** — the dataset is a regression log, not a sample of traffic, and a
complaint fixed without a row comes back.

**Choose the near-miss count before writing the positives.** A rate can only take
the values its denominator allows: on 28 near-miss rows a `<= 0.05` gate permits
exactly one miss and fails on the second. The count also sets how small a
regression the suite can see at all. Before fixing a count, read
[`SIZING.md`](SIZING.md).

**The rule walk sets the floor, the gate sets the target.** Nine rules yield nine
near-misses; gate on a rate that needs 28 and you owe nineteen more, as reported
false positives plus minimal pairs on the rules that misfire most often. A
component with too few rules to reach the count is not padded up to it: it gates
on a count rather than a rate, which is `SIZING.md`'s own rule.

**Assert two families by name rather than through the aggregate.** An aggregate
hides a regression by design — one new false positive in twenty-eight sits inside
a 5% gate and is still a bug you shipped. Both families exist in any component:

- the **evasion family** — rows differing from one you already handle only by a
  transformation the component is meant to be blind to: encoding, spacing and
  padding for a detector, a misspelling or a synonym for a retrieval query, a
  rephrasing for a classifier. One slip there means the shared normalising step
  regressed, and the aggregate barely moves.
- the **complaint family** — every row that came from a real reported failure.
  Each one has already cost somebody a support thread, so a regression there is a
  repeat, and the user reporting it the second time stops reporting.

For a guardrail, this dataset is what stops **Agent Goal Hijack (ASI01)**
reopening: the guardrail is the control, the dataset is what keeps it from being
narrowed away one reasonable-looking commit at a time.

**Rows generalise past detectors.** For a retrieval layer each row pairs a
question with the document that should come back — **including rows whose expected
result is nothing at all**. Those expected-misses are that component's near-miss
half; without them the layer answers unrelated questions while passing every test
it has.

**With no suite at all and a change to ship today, the first pass is three rows,
not a dataset:** the rule the change actually touches, one evasion row against it,
and one complaint row — or, before anyone has complained, the near-miss that pairs
that rule. Each is asserted by name in the file the change lives in. Three
rows that fail by name beat a sixty-row set next quarter, and every rule above is
a way to grow them.

**The dataset is finished when** every rule in the component's list has at least
one near-miss row, every positive has a paired near-miss, and every false positive
anyone has reported has a row. Short of that, the score measures the rows someone
found easy to write.

## Level 2 — the golden set

A **level-2 eval** runs the real component against a real model on labelled rows.
Tag it to run on demand and before a release, and keep it out of the commit gate:
it needs a key, it costs money, it depends on someone else's rate limit.

**Build it around the boundary** — the request that is vague but servable, the
hostile message that also carries a genuine question, the plausible request
nothing you built serves. Straightforward rows catch a catastrophic regression
and nothing else.

**Ask where each label came from.** A label written by the author of the prompt
under test, or generated by the model under test, measures agreement with the
thing being tested, and the row passes by construction. Have two people label a
sample independently, and **set no gate above the rate at which they agreed** — a
threshold above your own labelling agreement measures the labellers.

**When they agreed on only 0.70 of the sample, the labels are the defect and the
gate is not where you absorb it.** Read the rows they split on — usually two rules
of the component's own policy collide there. Rewrite the rule until a third person
reproduces the labels and measure agreement again; rows still ambiguous after that
are not gate material. A 0.70 gate over a set nobody can label twice is a number
that moves when the labellers do.

**Keep the golden rows disjoint from the prompt's few-shot examples**, for the
same reason. Diff the two files; the intersection should be empty.

**A judge is an unevaluated classifier.** When the scorer is itself a model —
grading an answer for helpfulness, faithfulness or tone — it carries every defect
you are gating against, and none of them have been measured. Before one of its
verdicts gates anything, give it its own human-labelled set — illustratively 50
rows, sized off [`SIZING.md`](SIZING.md) like any other — and score it exactly as
above, agreement ceiling and low-agreement remedy included. Then ask it for a
**pairwise** verdict against a baseline answer — which of these two is better —
rather than an absolute 1–5 score it cannot hold steady between runs, and present
each pair in both orders, counting a win only when it survives the swap: that
removes position bias and most of the judge's preference for the longer answer.

**A tool-description change is a level-2 change**, proved by a set of user turns
each labelled with the item that should fire: no code changes, so no functional
test can break. This skill owns how a dataset is built and sized;
[`../reviewing-agent-tools-and-skills/ROUTING-SET.md`](../reviewing-agent-tools-and-skills/ROUTING-SET.md)
owns that set and the before/after run it feeds. What follows applies to it as to
any live set.

**One run is one sample — here, not at level 1.** A deterministic suite reproduces
its own number exactly; a live one does not, so a gate set at the number you
measured once will flap. Measure **five runs**, set the gate below the worst, and
treat *re-running until green* exactly as you treat editing a case until green. If
the spread across five runs is wider than the regression you want to catch, the
gate cannot see it at all.

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
the 40 servable rows; on 40 rows nothing finer than about 9 points is visible
(`SIZING.md`)*, is a number someone argues about before lowering it. A bare `0.05`
is not.

Read that third figure off the denominator the rate actually divides by, not off
the size of the set: a false-refusal rate is estimated from the servable rows
alone, and there are always fewer of those than there are rows.

## Drift and skipped: two things a run reports without failing on

A build going red for something that is not a regression teaches the team to
ignore red, which costs you the gate itself. Two cases earn a report instead.

**Labels that moved are drift: print them, do not gate them.** Gate the outputs
that change what the user gets; a secondary label, a confidence band or a chosen
template moves on every model version for reasons unrelated to your change. Drift
is still a regression waiting for a consumer — a decision that stayed right while
its label moved breaks whatever keys on that label. Promote a pair into the gate,
and out of the drift report, the moment a metric dimension, a template lookup or a
routing hint reads it.

**A skipped case is not a failure.** A quota error, a provider 5xx and a timeout
are not wrong answers. Count them **skipped** and exclude them from both
denominators. Scoring an infrastructure error as a classification error breaks the
number in the most expensive direction: it looks like a quality regression, so
someone spends an afternoon changing a prompt that was fine.

**Then fail the run if too few rows ran.** Without that floor the skip rule
degenerates into its own worst failure: every row rate-limited means zero rows
ran, and accuracy over zero rows offends no threshold.

## When the case is the thing that is wrong

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

When a run comes back red for a reason nobody can act on, green on a handful of
rows, or red on the day the change must ship, read
[`RUNNING-A-SUITE.md`](RUNNING-A-SUITE.md) — pacing, the `ran / total` floor, the
drift printout, and what an override records.

## Reviewing an eval suite

Ask, in order:

1. How many rows ran on the last green run? A suite that passed on 3 of 62 rows
   is the failure that looks most like success.
2. Which gate is satisfied by doing more of the thing you are trying to limit? A
   number maximised by refusing everything needs a second with a different
   denominator.
3. Where did the labels come from, and do any of the rows also appear in the
   prompt?
4. Does every edited label in the dataset's history carry a reason, in its own
   commit?
