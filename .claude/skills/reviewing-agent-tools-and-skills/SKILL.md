---
name: reviewing-agent-tools-and-skills
description: Run when an existing tool and skill layer needs sweeping for routing, contract and skill-body defects, when the model keeps picking the wrong tool or skill, or before a description or name change ships — fixing each defect without moving traffic you did not mean to move.
disable-model-invocation: true
---

# Sweeping a tool and skill layer

**Routing** is which tool or skill the model picks for a turn, and no test suite exercises it:
every tool passes every test it owns while the model reaches for the wrong one on every turn,
and the graph that would show you goes down slowly enough to look seasonal.

This is a sweep, not a review of one tool. **Every check below needs the whole layer** — an item
here is wrong only relative to its neighbours, to its traffic, or to its own text a month ago. A
check that runs against one item sitting alone belongs to a **single-item review**:
`agentic-tool-boundary`'s *Reviewing an existing tool layer* for a tool,
`authoring-agent-skills`'s *Reviewing a skill document* for a skill. Every deferral below goes to
one of those two.

| You are here because | Start at |
|---|---|
| a description, a name or the exposed set is about to change | *A description change is a behaviour change* |
| you are starting a sweep | *Order of operations*, then [`PASSES.md`](PASSES.md) |
| every item needs a free-tier score | *The rubric*, then [`RUBRIC.md`](RUBRIC.md) once an item misses full marks |
| the free tier bought a routing run | [`ROUTING-SET.md`](ROUTING-SET.md) |
| two tools compete for the same turns, or one has zero calls | [`MERGE-AND-ZERO-CALL.md`](MERGE-AND-ZERO-CALL.md) |
| two skill descriptions overlap, or merges have landed and bodies need sweeping | [`SKILL-OVERLAP-AND-BODIES.md`](SKILL-OVERLAP-AND-BODIES.md) |
| the sweep is finishing | *What the sweep produces* |

## A description change is a behaviour change — and so is a rename

Editing a description ships new behaviour with no code diff to review. Illustrative: widening a
search tool's description to also mention "documents" pulled support turns off the ticket
lookup and into search, which answered from a stale corpus in a confident voice. Nothing threw,
ticket-lookup volume fell ~40%, and nobody read that graph for weeks.

**A name is a routing signal too, and it is read before the description.** `utils` claims no
territory and no description repairs it (`authoring-agent-skills`); renaming `search_incidents` to
`find_incidents` moves turns whether or not a word of prose changed. So the gate's scope is **any
change to a routing signal** — the description, the name, which items are exposed at all, and the
order they are exposed in. Four kinds of change, one gate. The trap this closes: a rename is the
routing change a sweep produces most often and the one it is most tempted to wave through as
cosmetic, so a tool ships a new name with no before/after run inside a sweep that blocks a one-word
description edit over a single unpredicted cell.

So every such change passes a **routing gate**: run the **routing set** — recorded user turns,
each labelled with the item that should have fired, held **frozen** for the duration (§ *The routing
set*) — against the layer before the edit, run it again after, and compare the two **confusion
tables** — rows are the labels, columns are what actually fired — cell by cell.

- **The gate reads a block of the table, not the table.** Its rows and columns are the changed item,
  the items sharing its **primary subject**, any item either description hands ground to by name,
  and the ***none*** row and column — three to six items plus *none*, so twelve to forty-two
  off-diagonal cells. **Inside the block, every cell that moved beyond the noise floor must have
  been named before the new text was written** (floor per `RUBRIC.md`, so a one-turn wobble is not
  a finding). Unnamed movement blocks the ship: a turn arriving from a neighbour you did not
  predict, and equally a turn leaving, **including a gain** — an unclaimed gain is an edit whose
  mechanism you cannot state, which makes the next one a guess too.

Widening `returns` to also mention deliveries, predicted beforehand as *"four turns labelled
returns move off `order-tracking`"*. Measured floor 1; parentheses are the change from the
before-run:

```
                fired →   returns   order-tracking   refunds   none
label ↓
  returns                 31 (+4)     2 (−4)            0        1
  order-tracking           3 (+3)    23 (−3)            0        1    ← unpredicted: blocks
  refunds                  0          1 (+1)           17 (−1)   0    ← inside the floor: not a finding
  none                     2 (+2)     0                 0        5 (−2)  ← unpredicted: blocks
```

The predicted pair moved as predicted, and the edit still does not ship: two cells nobody named
moved past the floor, one theft from a neighbour and one over-firing on turns the layer declined to
serve. Each row's deltas sum to zero because the set is frozen and a row is just the count of turns
carrying that label — a row that does not sum to zero is a run you cannot compare to anything.

- **Outside the block, scan; do not predict.** Forty items plus *none* is a 41×41 table, 1640
  off-diagonal cells, nearly all zero in both runs — and a gate demanding a named prediction for
  each never passes, so it gets skipped whole. Movement out there still blocks the ship, but as a
  finding rather than a missed prediction: the edit reached past its subject, so either the index
  filed the item wrong or the text says more than you think it says.
- **A description edit never rides with an edit to its parameters or its handler.** When routing
  moves you must be able to name the one line that moved it.
- **Draft neighbouring descriptions together; gate them one at a time.** `authoring-agent-skills`
  drafts a neighbouring pair side by side, and that is right — the overlap is a property of the
  pair, not of either text. Shipping the pair together is the separate mistake: the traffic that
  moves between them is attributable to neither. Edit twelve neighbouring descriptions, route once,
  and you learned one bit. Items at opposite ends of the catalogue, sharing no subject, can batch.

## Order of operations

| Pass | Costs | What only this pass finds |
|---|---|---|
| 1. Inventory | two log queries, one descriptor dump, one activation and one captured request per skill | each item's name, description text, call or activation count over the window, its **primary subject** — the noun it is actually about, in three words — and, per skill, the share of activations followed by no call to any tool it discloses |
| 2. Drift | a `diff` | a description, or a routing-set turn, that changed since the last sweep without a change of yours |
| 3. Overlap → [`MERGE-AND-ZERO-CALL.md`](MERGE-AND-ZERO-CALL.md), [`SKILL-OVERLAP-AND-BODIES.md`](SKILL-OVERLAP-AND-BODIES.md) | reading, bounded by subject; 40 sampled turns and a domain reader per merge test | two items competing for the same turns |
| 4. Contract consistency | reading the shipped schema, bounded by subject | neighbours that disagree about a concept they share |
| 5. Correction rate, **tools only** | one log query — only if every call carries the id of the user turn it belongs to | tools whose first call gets abandoned more often than the rest |
| 6. Routing run → [`ROUTING-SET.md`](ROUTING-SET.md) | a model call per single-label turn, per candidate — and two or more, plus a stub fixture, per sequence turn | reach and exclusivity, measured instead of argued |
| 7. Blind A/B → *Blind A/B* | the routing run again, once per candidate | which of two rewrites is actually better |
| 8. Body sweep → [`SKILL-OVERLAP-AND-BODIES.md`](SKILL-OVERLAP-AND-BODIES.md) | reading, one grep | a body naming a tool this sweep just merged away or retired |

**Passes 2–5 and 8 spend no model calls, and pass 1 spends one round trip per skill and none per
tool**, and that is all *free* means here. It is not free of time: pass 3's merge test spends a
domain reader on 40 turns per candidate pair, the scarcest thing in the sweep — which is what pass
1's subject index is for, keeping the pairs down to the ones that can actually collide. Passes 6–7
are bought only for what the free tier flagged: thirty turns per item across forty tools is a
1200-turn set, and most of those forty were fine before you started. Pass 8 runs last, after every
merge and retirement has landed, because it cleans up after them.

→ [`PASSES.md`](PASSES.md) — passes 1, 2, 4 and 5 in full: inventorying what the
framework sends rather than what the source says, the primary-subject index, sizing
the window, snapshot diffs and what *frozen* means, neighbours that disagree on a
shared concept, why most layers cannot run the tool correction query, and the
pre-launch variant, where the routing run is the only measurement there is.

## The rubric

Score every inventoried item on the free tier. The two counted rows are **ranks within your own
layer**, never absolutes — an absolute threshold compares your layer against an industry number
nobody measured. Calibrate them once from your own distribution, then freeze.

| Free tier | 0 | 1 | 2 |
|---|---|---|---|
| **Traffic** — calls in the window, against the items sharing its primary subject | zero | an order of magnitude under that group | comparable to the group, or non-zero with no subject-sharing peer |
| **Overlap** — against its nearest neighbour | shares its primary subject | shares a trigger phrase | no neighbour shares a trigger phrase |
| **Contract consistency** — against the items sharing its primary subject | disagrees with one on a shared concept | consistent, but undocumented | consistent and documented |
| **First-call correction** — its calls followed by a different item for the same goal | over a quarter | between a twentieth and a quarter | under a twentieth |

**A skill scores the same four rows, read differently** — two of them presume a schema it does not
have, and an undefined row is how a skill leaves the sweep silently unscored. Traffic counts its
**activations** against the skills sharing its subject, which pass 1 already collected, and Overlap
is unchanged, since subjects and trigger phrases are text and a skill has both. The other two:

| On a skill | 0 | 1 | 2 |
|---|---|---|---|
| **Contract consistency** — its hand-off clause, in place of a schema | a neighbour claims ground it also claims and neither names the other, or the ground it hands over is handed straight back | it hands the shared ground over by name, and the neighbour is silent about it | both name the same owner for it, or nothing shares its subject or a trigger phrase, so there is no ground to hand over |
| **First-call correction** — activations followed by no call to any tool it discloses | over a quarter | between a twentieth and a quarter | under a twentieth |

The hand-off round trip in that first 0 is the one defect no per-skill review can reach, because
each description reads fine alone — `authoring-agent-skills`'s *Reviewing a skill document* asks
for the hand-off one pair at a time, and this row is the scored, population-wide version of it. 
**The skill's correction row comes from pass 1, and it is measurable on layers where the tool row is
not.** An activation and the calls that follow it are one turn, so the row needs no goal attribution
and no turn ids — only the activation log pass 1 is already standing in front of. Marking it *not
yet measurable* because pass 5 could not run is the mistake that leaves every skill in the catalogue
a row light, on the one row that sees an activation nothing followed.

**On a launched layer, full marks ships unchanged**, recorded as *reviewed, no change*. Not
editing is a result of the work, not an omission from it — every rewrite is a routing risk you
chose to buy. Anything less buys the routing run, which adds **Reach** (turns labelled for it that
it missed) and **Exclusivity** (turns labelled for a neighbour that it took).

**Record which rows you scored, not just what they scored.** A row whose denominator does not exist
scores *not yet measurable*, never 0, and the row count travels with the verdict — *reviewed, no
change (3 rows)* is a weaker claim than the same three words over five, and nothing else in the
summary says so. A 0 and an unmeasured row look identical there and mean opposite things: a 0 is a
finding about the item, an unmeasured row is a finding about your instrumentation. `RUBRIC.md` has
which is which.

**An item that lost a row reaches the routing run before it reaches a band.** The rows you could not
score are the ones that see a description no real turn matches, and that is what the run measures
directly. The pre-launch rule in [`PASSES.md`](PASSES.md) is this rule with two rows gone instead of one.

→ `RUBRIC.md` — the bands, read off the rows rather than off a total, and the verdict each one
buys; paid-tier anchors, calibration, worked scoring, the noise floor, and how to run the blind A/B.
Open it the moment an item misses full marks.

## The routing set

A **routing set** is a list of recorded user turns, each labelled with the item that
should have fired — built from logs, never invented, and holding three kinds of turn
besides the obvious: a neighbour's, labelled with the neighbour; turns nothing should
take, labelled *none*; and ordered pairs, budgeted separately →
[`ROUTING-SET.md`](ROUTING-SET.md). Open it before the first run.

## Blind A/B

**Reorder the exposed list and re-run the set before you rewrite anything.** Position bias is real
— an item listed first gets picked more — so exposure order is a property you can fix, and fixing
it risks no wording and needs no candidate text at all.

When two candidate rewrites do exist, two people will argue and the argument carries no evidence.
Run both instead, and buy the whole procedure rather than the parts you remember: authorship
withheld, position randomized, everything else held identical, the floor measured before you believe
any gap, and a decision rule that is not the hit rate. `RUBRIC.md` has all five. The one that gets
skipped is the last, by an experiment that is otherwise correct, because by then the winner looks
obvious. Run none of it from memory.

## The merge test — two tools that answer the same question

Strip the names from twenty turns of each tool and ask a domain reader which one
each turn belonged to; if they cannot tell, neither can the model. Then ask whether
a value the turn always supplies or a decision separates the two — merge,
subordinate or re-cut, never leave the overlap →
[`MERGE-AND-ZERO-CALL.md`](MERGE-AND-ZERO-CALL.md).

## The zero-call fork — where a Traffic 0 goes

Zero calls is a finding, not a verdict. Write the turn the item exists for and see
where the routing set sends it: retire it, fix a neighbour's routing defect, or keep
it behind an activation — and retire in two steps →
[`MERGE-AND-ZERO-CALL.md`](MERGE-AND-ZERO-CALL.md).

## Two skills whose descriptions overlap

A shared trigger goes to the skill that owns the **outcome** the user asked for, and
the loser records a hand-off clause; the tool dispositions do not carry over, and
the repair itself is `authoring-agent-skills`' →
[`SKILL-OVERLAP-AND-BODIES.md`](SKILL-OVERLAP-AND-BODIES.md).

## Skill bodies

Pass 8 runs last and greps every body in the catalogue for a name this sweep merged
away, retired or renamed; a body leaves the sweep as *body clean* or *body edit
queued* →
[`SKILL-OVERLAP-AND-BODIES.md`](SKILL-OVERLAP-AND-BODIES.md).

## What the sweep produces

Done means **every inventoried item carries a score with the rows behind it and exactly one of the
six item verdicts** — pre-launch included, where the verdict comes off the routing run's cells —
and **every skill body carries *body clean* or *body edit queued***.

| Verdict | Reached from |
|---|---|
| *reviewed, no change* | full marks on a launched layer's free tier, or the routing run's top band |
| *edit queued behind the gate* | the middle band — one targeted change to the text that is there |
| *rewritten, not edited* | the bottom band; either half of a re-cut pair; the surviving half of a merge at either layer — new text drafted from the subject or from the union, then A/B'd against the incumbent |
| *merge into `<named item>`* | the disappearing half of a merge at either layer — the merge test's *Merge* row for two tools, a shared primary subject for two skills |
| *moved behind an activation* | the zero-call fork, third row — which is where the mechanism is written down |
| *retire in two steps* | the zero-call fork's first row, the subordinated half of a tool subordination, and the absorbed half of a skill absorption |

Six verdicts per item, and that set is closed. **Every pairwise disposition spends two of them**, one
per half, and one rule tells the two kinds of disappearance apart: **the half that goes is *merge
into `<named item>`* when the survivor's text is rewritten against the union, and *retire in two
steps* when the survivor already covered that ground and only gains an example.** So a merge, at
either layer, is *merge into `<named item>`* plus *rewritten, not edited*; a subordination, and a
skill absorption one layer up, is *retire in two steps* plus *edit queued behind the gate* for the
one that took its ground; a re-cut pair is two *rewritten* verdicts; an assigned phrase is two
*edit queued behind the gate*, gated one at a time. The two-step withdrawal is the procedure every
disappearance follows, not a seventh verdict — and a body's *body clean* or *body edit queued* is a
second axis, scored per skill, never one of the six.

**One verdict belongs to the layer instead of an item: *capability gap*.** It resolves no item's
score and is reachable from no band, and two passes surface it without either naming an item. Pass
5 finds a goal whose first call was abandoned for a *second* item that did not serve it either, so
the goal left the layer unanswered — on a layer carrying the turn id; without it pass 6 is the only
route to this verdict. Pass 6 finds a *none*-labelled turn that keeps arriving and was
labelled *none* for want of an item, not by decision. Neither is fixed by editing text: the gap
leaves the sweep as a request, and `authoring-agent-tools` is where it goes.

Every queued edit names, by label, the routing-set turns it must not regress, named before the new
text is written. The deliverable is the confusion diff: every turn that moved, each marked intended
or reverted. A sweep that produces only prose measured nothing.

An item you read and formed no opinion about is not finished. It scores, or the inventory is wrong.
