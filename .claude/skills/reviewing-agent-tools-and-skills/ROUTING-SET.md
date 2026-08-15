# Building the routing set

Open this before the first run. `SKILL.md` says what the set is for and how it
is change-controlled; this file is how you build one that can fail.

## Where the turns come from

**Sample from logs, stratified by item.** Take turns per item, not turns at
random: a random sample of production traffic is dominated by whatever is
popular, and the items you are sweeping are usually not that.

Three seams pay for themselves:

- **Turns the model got right.** The baseline. Cheap, plentiful, and they prove
  nothing on their own.
- **Turns followed by a corrected retry** — the model called something, then
  called something else for the same user goal. That correction is a label
  someone already wrote for you.
- **Turns followed by the user rephrasing.** The model answered, the user asked
  again in different words. Usually a routing miss that never threw.

Label each with the item that *should* have fired, not the one that did.
Labelling from the log's behaviour reproduces the bug you are measuring.

## Size

20–40 recorded turns per item is a workable start (illustrative). Size per item,
not overall, or a busy item drowns the neighbour it is stealing from.

Growth rule: when a sweep finds a defect the set did not catch, the turn that
exposed it joins the set with its label, in the same commit as the fix. A set
that never grows is a set that stopped finding things.

## Four kinds of case

| Kind | Label | What it measures |
|---|---|---|
| **Own** | the item itself | reach — does its own traffic arrive |
| **Boundary** | the neighbour, deliberately | exclusivity — a turn a reasonable person could file under either |
| **Orphan** | *none of them* | over-firing — a turn nothing in the layer should take |
| **Sequence** | an ordered pair | the precondition, and whether the model stops halfway |

Boundary turns are the half people skip and the half that catches theft. Write
one for every adjacent pair the subject index found, and write down *why* the
label is what it is — "reads like a delivery question; the answer is in the
return window" — because a boundary turn with no stated reason gets relabelled
by whoever disagrees with the result.

## Writing a sequence case

A sequence case labels a turn with an ordered pair, and it exists because the
characteristic multi-tool failure is invisible to single-label scoring: the
model picks the right first item, then stalls, or skips it entirely and calls
the second with a value it invented.

```
turn      "what's the weather at the office in São Paulo"
label     find_place → get_weather
```

Score it three ways, and keep them apart:

- **Both, in order** → pass.
- **Right first item, no second call** → *stalled*. The model has what it needs
  and does not continue. This is a body or description defect, not a routing
  one — the first item's result never said what to do next.
- **Second item called first** → *precondition skipped*. The model fabricated
  the argument the first call was supposed to produce. Every downstream answer
  is confidently wrong, and nothing in the trace is an error.

Roll these up as three counts, never as one hit rate. A set that reports 80% and
hides that a fifth of its sequence cases fabricated a coordinate has measured
the wrong thing.

## Running it

Hold the model version, the temperature, the exposed neighbour set and the
system prompt fixed across every run you intend to compare. Record all four in
the run's header — a comparison against a run whose model version you cannot
name is not a comparison.

Output is a confusion table: rows are labels, columns are what fired.

```
                 fired →  order-tracking  returns  none
label ↓
order-tracking              27              3        0
returns                      6             22        2
none                         1              0        4
```

Read the off-diagonal cells, one at a time. The `returns → order-tracking` cell
at 6 is the finding; the 82% overall accuracy that the same table produces is a
number that would look fine in a report and hide it.

## Pre-launch, with no traffic

There is no log to sample and the set still has to exist. Sources, in
descending order of how close they are to real phrasing:

1. **Support tickets and chat transcripts** for the same problem domain, from
   whatever channel handled it before.
2. **Search queries** into your docs or product search — short, unpolished, and
   already in the user's vocabulary.
3. **Questions your documentation gets asked**, from issues, forums or the
   people who answer them.

Do not write the turns yourself, and especially not after writing the
descriptions: you will reuse the description's vocabulary and it will route them
by construction. If you genuinely have none of the three sources, have someone
who has never read the descriptions write the turns from a one-line statement of
what the product does.

**Size it by the phrasing you found, not by turns per item.** The 20–40 above
assumes a log; pre-launch you have whatever those three sources gave you, and
padding to a quota with turns you wrote yourself puts the rigged green straight
back. Spend what you have on **boundary** turns first — one per adjacent pair the
subject index found — and read the result per subject group rather than per item.
Whether an item's own traffic arrives is not answerable before there is traffic;
whether the boundary between two neighbours holds is, and it is the only thing a
pre-launch run can tell you.

Mark the set as pre-launch in its header, and replace its turns with recorded
ones as soon as the window exists — the pre-launch set is scaffolding, and its
hit rate is worth less than a recorded set's confusion table.
