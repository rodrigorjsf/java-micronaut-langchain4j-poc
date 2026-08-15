---
name: agentic-evals
description: Prove a prompt, classifier or tool-description change safe before it ships. Use when a prompt changes and someone has to show nothing regressed, when building a labelled dataset for a guardrail or classifier, when deciding what a build gates on versus what it only reports, when a reworded tool description might steal traffic from a sibling tool, or when a case fails and the case may be the thing that is wrong.
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
| compaction preserves the state that gates behaviour | a summarised-away capability marker, so the agent silently loses its tools |
| the standing prompt is byte-identical across turns | an interpolated timestamp that ends every prompt-cache hit |

The compaction row is what *keeps* **Memory & Context Poisoning (ASI06)** in the
OWASP Top 10 for Agentic Applications 2026 closed: the compaction rule is the
control, and without the assertion it holds only until the next person edits the
summariser.

**A failing gate names the row, not only the number.** `expected >= 0.90, got
0.87` costs a re-run with logging turned on before anyone can start work. Print
every miss with its id and family.

## Building the near-miss half

The dataset is a file in the repository, reviewed like code, **rows in a stable
order — never generated, never shuffled**; several rules below are enforced by
reading a diff.

A row is `{id, family, label, reason, text}`. `id` is stable, so a failure names a
row you can open; `family` groups rows by the mechanism they exercise, so a
failure names what to look at; `reason` is one line written when the row is
created, and is the only defence you have the day the row fails.

The positive half writes itself. **The benign half decides whether the component
stays switched on**, and it is usually an afterthought — obviously normal
messages no rule would fire on, proving nothing.

**Derive the candidates from your own rules, then pair each one.** Walk the list
of patterns, keywords and thresholds the component fires on; for each, write the
sentence a real user would send that contains it, and keep it beside the positive
it neighbours — same words, different intent.

```
BAD    benign rows written as "normal traffic"
       "hi"   "thanks!"   "where is my order"

GOOD   walking a destructive-intent detector's list (delete/cancel/reset/remove)
       positive   "delete my account"
       near-miss  "what happens to my data if I delete my account?"

       positive   "cancel everything on this account"
       near-miss  "how do I cancel my subscription at the end of the term?"
```

A **minimal pair** differing in one clause tells you which clause your rule keys
on. A positive lifted from a security write-up and a benign row lifted from a
product FAQ differ in a hundred ways, and a failure on either tells you nothing.

**Every false positive anyone reports becomes a permanent row, before the fix
lands.** The dataset is a regression log, not a sample of traffic; a complaint
fixed without a row comes back.

**Choose the benign count before writing the positives.** A rate can only take
the values its denominator allows: on 28 benign rows a `<= 0.05` gate permits
exactly one miss and fails on the second, and on 5 rows it permits none at all
while reading as tolerant. Size sets resolution too — 62 rows scoring 0.90 put
the true rate within roughly ±7.5 points, so a two-point regression is invisible
there and resolving one takes about a thousand rows. Before fixing a count, read
[`sizing.md`](sizing.md).

**Assert the two families that must be perfect, individually.** An aggregate
hides a regression by design — one new false positive in twenty-eight sits inside
a 5% gate and is still a bug you shipped. The **evasion set**, rewrites of a
positive you already catch (encoding variants, spacing, padding), where one slip
means the normalising step regressed and the aggregate barely moves. And the
**tricky benign set**, near-misses that were once real complaints, each asserted
by name.

For a guardrail this dataset is what keeps **Agent Goal Hijack (ASI01)** from
reopening. The dataset is not the control — the guardrail is. It stops the
control being narrowed away one reasonable-looking commit at a time.

**The procedure generalises past detectors.** For a retrieval layer each row pairs
a question with the document that should come back — **including rows whose
expected result is nothing at all**. Those expected-misses are the benign half;
without them the layer passes every test it has while answering unrelated
questions anyway.

**The dataset is finished when** every rule in the component's list has at least
one benign row, every positive has a paired near-miss, and every false positive
anyone has reported has a row. Short of that, the score measures the rows someone
found easy to write.

## Level 2 — the golden set

A **level-2 eval** runs the real component against a real model on labelled rows.
Tag it to run on demand and before a release, and keep it out of the commit gate:
it needs a key, it costs money, it depends on someone else's rate limit.

**Build it around the boundary.** Straightforward rows catch a catastrophic
regression and nothing else. The rows that earn their place are the ones a careful
colleague could argue about — the request that is vague but servable, the hostile
message that also carries a genuine question, the plausible request that nothing
you built actually serves.

**Ask where each label came from.** A label written by the author of the prompt
under test, or generated by the model under test, measures agreement with the
thing being tested, and the row passes by construction. Have two people label a
sample independently, and **set no gate above the rate at which they agreed** — a
threshold above your own labelling agreement measures the labellers.

**Keep the golden rows disjoint from the prompt's few-shot examples**, for the
same reason. Diff the two files; the intersection should be empty.

**A judge is an unevaluated classifier.** When the scorer is itself a model —
grading an answer for helpfulness, faithfulness or tone — it carries every defect
you are gating against, and none of them have been measured. Before one of its
verdicts gates anything, give it its own small human-labelled set and score it
exactly as above. Then ask it for a **pairwise** verdict against a baseline
answer — which of these two is better — rather than an absolute 1–5 score it
cannot hold steady between runs. Present each pair in both orders and count a win
only when it survives the swap: that one move removes position bias and most of
the judge's preference for whichever answer is longer.

**A tool-description change is provable here and nowhere else:** a set of user
turns, each labelled with the tool that should be selected. A rewording that
steals traffic from a sibling tool changes no code and breaks no functional test.

**One run is one sample — here, not at level 1.** A deterministic suite
reproduces its own number exactly; a live one does not, so a gate set at the
number you measured once will flap. Measure **five runs**, set the gate below the
worst of them, and treat *re-running until green* exactly as you treat editing a
case until green. If the spread across five runs is wider than the regression you
want to catch, the gate cannot see it at all.

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

**Every threshold constant carries three things** — the expensive error it is
named after, how many failures it actually permits, and the smallest change the
suite can resolve. A bare `0.05` is a number nobody defends when it fails.
`MAX_FALSE_REFUSAL_RATE = 0.05`, annotated *a real user turned away; 2 of the 40
servable rows; below ±7 points this suite cannot tell a change from noise*, is a
number someone argues about before lowering it.

## Drift: report what you cannot act on

Gate the outputs that change what the user gets. **Print** the outputs that only
change a dimension — a secondary label, a confidence band, a chosen template:

```
intent drift (advisory, not gated):
  address  -> company     3
  greeting -> smalltalk   1
```

A secondary label moves on every model version for reasons unrelated to your
change, and a build going red for a reason nobody acts on teaches the team to
ignore red. Drift is still a regression: a decision that stayed right while its
label moved breaks whatever keys on that label — a metric dimension, a template
lookup, a routing hint. Promote a pair to a gate the moment one of those exists.

## A skipped case is not a failure

A quota error, a provider 5xx and a timeout are not wrong answers. Count them
**skipped** and exclude them from both denominators. Scoring an infrastructure
error as a classification error breaks the number in the most expensive
direction: it looks like a quality regression, so someone spends an afternoon
changing a prompt that was fine.

**Pace the runner under the measured limit.** Free tiers cut off far lower than
the paid documentation suggests — one measured at roughly 10–20 requests per
minute, so the runner waits ten seconds between rows. Measure yours rather than
reading it off a pricing page.

**Fail the run if too few rows ran.** Without this floor the skip rule degenerates
into its own worst failure: every row rate-limited means zero rows ran, accuracy
over zero rows offends no threshold, and the suite is green while measuring
nothing. Require at least half the rows, and print `ran / total` every run.

## When the case is the thing that is wrong

Sometimes the component is right and the label was wrong. This is the moment the
suite is most likely to be quietly destroyed, so it gets a bar.

**Change a row only when you can state the rule it now violates without
mentioning the build.** "The scope document puts questions about the assistant in
scope, and this row is labelled out" is a reason; "it fails now" is not. The
discriminator: would you have labelled it this way *before* seeing the failure? If
you cannot answer that without reading the diff, the row stands and the change
under test is the thing that is wrong.

**When the row was genuinely ambiguous, splitting beats editing.** Replace it with
two rows that are each unarguable, or move it to the advisory drift set. A row two
people read differently measures the reader.

**Change the row in its own commit,** separate from the change that made it fail,
with the reason in the message. A golden set edited quietly to make a build green
is worse than no golden set: it still prints a number, and the number no longer
means anything.

**When the gate fails and the change ships anyway, override it by name** — one
documented switch recording who overrode which gate and when the override
expires. The move people reach for instead is lowering the constant, which is
permanent, silent, and afterwards indistinguishable from a threshold someone
reasoned about.

## Reviewing an eval suite

Ask, in order:

1. How many rows ran on the last green run? A suite that passed on 3 of 62 rows
   is the failure that looks most like success.
2. Which gate is satisfied by doing more of the thing you are trying to limit? A
   number maximised by refusing everything needs a second with a different
   denominator.
3. Where did the labels come from, and do any of the rows also appear in the
   prompt?
4. In the dataset's history, does every edited label carry a reason, in its own
   commit?
