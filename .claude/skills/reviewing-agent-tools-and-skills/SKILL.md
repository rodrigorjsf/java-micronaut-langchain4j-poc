---
name: reviewing-agent-tools-and-skills
description: Sweep an existing tool and skill layer for routing, contract and skill-body defects, and fix them without moving traffic you did not mean to move.
disable-model-invocation: true
---

# Sweeping a tool and skill layer

**Routing** is which tool or skill the model picks for a turn, and no test suite exercises it:
every tool passes every test it owns while the model reaches for the wrong one on every turn,
and the graph that would show you goes down slowly enough to look seasonal.

This is a sweep, not a review of one tool. **Every check below needs the set** — an item here
is wrong only relative to its neighbours, to its traffic, or to its own text a month ago. A
check that runs against one tool sitting alone belongs to a single-item review.

## A description change is a behaviour change

Editing a description ships new behaviour with no code diff to review. Illustrative: widening a
search tool's description to also mention "documents" pulled support turns off the ticket
lookup and into search, which answered from a stale corpus in a confident voice. Nothing threw,
ticket-lookup volume fell ~40%, and nobody read that graph for weeks.

So every description edit passes a **routing gate**: run the frozen set against the layer
before the edit, run it again after, and compare the two **confusion tables** — rows are the
labels, columns are what actually fired — cell by cell.

- **The gate passes when every off-diagonal cell that moved beyond the noise floor was named
  before the new text was written** — floor measured per `RUBRIC.md`, so a one-turn wobble is
  not a finding. Unnamed movement past it blocks the ship: a turn arriving from a neighbour you
  did not predict, and equally a turn leaving, **including a gain**. An unclaimed gain is an
  edit whose mechanism you cannot state, which makes the next one a guess too.
- **A description edit never rides with an edit to its parameters or its handler.** When
  routing moves you must be able to name the one line that moved it.
- **One item per gate run when the items are neighbours.** Unrelated items at opposite ends of
  the catalogue can batch. Edit twelve neighbouring descriptions, route once, and you learned
  one bit.

## Order of operations

| Pass | Costs | What only this pass finds |
|---|---|---|
| 1. Inventory | one log query, one descriptor dump | each item's name, description text, call or activation count over the window, and its **primary subject** — the noun it is actually about, in three words |
| 2. Drift | a `diff` | a description, or a routing-set turn, that changed since the last sweep without a change of yours |
| 3. Overlap | reading, bounded by subject; 40 sampled turns and a domain reader per merge test | two items competing for the same turns |
| 4. Contract consistency | reading the shipped schema, bounded by subject | neighbours that disagree about a concept they share |
| 5. Correction rate | one log query | items whose first call gets abandoned more often than the rest |
| 6. Routing run | a model call per single-label turn, per candidate — and two or more, plus a stub fixture, per sequence turn | reach and exclusivity, measured instead of argued |
| 7. Blind A/B | the routing run again, once per candidate | which of two rewrites is actually better |
| 8. Body sweep | reading, one grep | a body naming a tool this sweep just merged away or retired |

**Run order is the table's; teaching order is this document's.** They part once: passes 6 and 7
are explained before pass 3, because pass 3 ends in decisions — merge this pair, retire that
item — you can only check by running the set. Every heading carries its pass number, and the
zero-call fork between them is not a pass at all: it is the rubric's Traffic-0 row resolved.

**Passes 1–5 and 8 spend no model calls**, and that is all *free* means here. It is not free of
time: pass 3's merge test spends a domain reader's attention on 40 turns per candidate pair,
which across a forty-item layer is the scarcest thing in the sweep — which is exactly what pass
1's subject index is for, keeping the pairs down to the ones that can actually collide. Passes
6–7 are bought only for what the free tier flagged: thirty turns per item across forty tools is
a 1200-turn set, priced per the row above, and most of those forty were fine before you
started. Pass 8 runs last — after every merge and retirement has landed, because it cleans up
after them.

**Pass 1 in full.** Inventory what the framework sends, not what the source says: a parameter
description blanked because a build step dropped it, and a skill whose tools never attached so
activating it changes nothing, both read perfectly in source. Then write each primary subject
down: all pairs in a forty-item layer is 780 comparisons, the subject index cuts pass 3 to a
handful, and that is what makes the free tier affordable across the whole set.

**Pass 2 in full.** Keep every description the layer exposes, and the labelled routing set
beside it, in checked-in snapshot files, and diff both at the start of each sweep. Text you do
not author — anything mounted from a third party — changes under you between releases and
arrives as prompt text the model obeys, so a description that gained a sentence you did not
write stops the sweep: the control for **ASI04 Agentic Supply Chain Vulnerabilities**. The set
earns the same discipline, changing only by a labelled commit naming which turns were added or
relabelled and why — a set edited quietly between the before run and the after run makes both
numbers meaningless.

**Pass 3 in full** is two sections of its own at the end of this document: *The merge test*, for
two tools answering one question, and *Two skills whose descriptions overlap*, the same defect
one layer up.

**Pass 4 in full.** Judging one tool's own arguments, result budget and error text is a
single-item review and this sweep does not repeat it. What only the population shows is
neighbours disagreeing — one concept spelled `state: "resolved"` here, `status: "CLOSED"` next
door and `incident_state: "done"` in a third tool, so a value read out of one result is an
argument error in the next call; a parameter documented with an example in one entry and blank
in its neighbour; two entries that truncate, one announcing it and one not. That last is worse
than a lone silent truncation: a model that has learned *these results say when they were cut*
reads a missing notice as completeness.

**Compare each item against the items sharing its primary subject, not against the layer** —
pass 1's index bounds this pass exactly as it bounds pass 3, on the same arithmetic. A chain
that crosses subjects is not caught by reading schemas anyway: that is what pass 6's sequence
cases exercise, where the invented argument shows up as behaviour instead of as a spelling you
happened to notice.

**Pass 5 in full.** One query per item: calls of it that were followed by a *different* item
being called for the same user goal. That rate indicts this item's description — it attracted a
turn it could not serve. **A retry of the same item with corrected arguments is not this row.**
That is an argument-contract defect belonging to a single-item review, and scoring the two
together sends you off to rewrite a description that was right. The same query is also the
cheapest source of new set turns (`ROUTING-SET.md`).

**A layer that has not launched has no window.** Passes 1–4 run on text alone, and the routing
run still runs against a set written from whatever real phrasing exists — support tickets,
search queries, the questions your docs get. The merge test and the zero-call fork need traffic
and are deferred whole; the Traffic and First-call correction rows score *not yet measurable*,
never 0.

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

**Full marks ships unchanged**, recorded as *reviewed, no change*. Not editing is a result of
the work, not an omission from it — every rewrite is a routing risk you chose to buy. Anything
less buys the routing run, which adds **Reach** (turns labelled for it that it missed) and
**Exclusivity** (turns labelled for a neighbour that it took).

**Record the denominator.** An unmeasurable row scores *not yet measurable* and leaves both
numerator and total, so a pre-launch layer scores out of 4 and ships unchanged at 4/4. A 0 and
an unmeasured row look identical in a summary and mean opposite things.

→ `RUBRIC.md` — the combined score bands and the verdict each one buys, paid-tier anchors,
calibration, worked scoring, the noise floor, and how to run the blind A/B. Open it the moment
an item misses full marks.

## The routing set — pass 6

A **routing set** is a list of recorded user turns, each labelled with the item that should
have fired.

**Build it from your logs.** Invented turns are worthless here: you write them with the
description in your head, so they reuse its vocabulary and it routes them by construction. The
turns that break routing are in the user's words — "where's my stuff", "the thing I ordered
last week" — and you will not invent those.

Three include-rules, each defending a score the set would otherwise inflate:

- **Turns that belong to a neighbour, labelled with the neighbour**, or the set measures reach
  and is blind to theft.
- **Turns nothing in the layer should take, labelled *none***, or the cheapest way to raise
  every reach score is to widen every description — and the paid tier becomes gameable by
  precisely the defect it exists to catch.
- **Turns whose label is an ordered pair**, or the multi-step failure stays invisible: the
  model picks the right first item and then stalls, or skips it and calls the second with an
  argument it invented. One label per turn can never see either.

**Budget the ordered pairs separately.** Telling a stall from a pass means letting the model
take a second step: hand the first call's result back as a stubbed fixture and see whether it
continues. Score a sequence turn from the single call you already made and every stall is
recorded as a pass — you never gave the model the chance to fail. Two or more calls and one
stub per sequence turn, and the three sequence outcomes stay three counts, never one rate.

→ `ROUTING-SET.md` — sourcing, sizing, boundary turns, writing a sequence case, reading the
confusion table, and the pre-launch variant. Open it before the first run.

## Blind A/B — pass 7

**Reorder the exposed list and re-run the set before you rewrite anything.** Position bias is
real — an item listed first gets picked more — so exposure order is a property you can fix, and
fixing it risks no wording and needs no candidate text at all.

When two candidate rewrites do exist, two people will argue and the argument carries no
evidence. Run both instead — and **decide on the confusion table, not the hit rate**. A
candidate that gains overall by absorbing its neighbour's boundary turns has lost, and the hit
rate that crowned it is the one number that cannot say so: it moves for two opposite reasons.
This is the step a correct experiment still gets wrong, because the winner looks obvious.

The mechanics that keep the comparison honest — authorship withheld, position randomized,
everything else held identical, the floor measured before you believe any gap — are in
`RUBRIC.md`. Run none of them from memory.

## The merge test — pass 3, two tools that answer the same question

Pull 20 turns that fired tool A and 20 that fired tool B, strip the tool names, and hand the 40
to someone who knows the domain. **If they cannot say from the turn alone which one should have
fired, the model cannot either** — it has been guessing all along, consistently, in whichever
direction the descriptions lean. Then ask what separates them: **a value the turn always
supplies, or a decision the model would be guessing at?**

```
BAD   search_active_incidents()   search_resolved_incidents()
GOOD  search_incidents(state: "active" | "resolved" | "all")
```

A turn about incidents always says which kind, so the split buys nothing and puts a second item
into every incident routing decision. The corollary is the line to hold while merging: **the
merged tool takes the union of the parameters, not a mode flag.** A parameter that selects
which of the two old behaviours you wanted is a decision, not a value the turn supplies — that
pair answers two different questions, and the fix is two sharper descriptions.

| Disposition | When | What you do |
|---|---|---|
| **Merge** | they differ only by a value the question supplies | one item, one enumerated parameter |
| **Subordinate** | one is a special case of the other | delete the special case; move its case into the general item's description as an example |
| **Re-cut** | the split is on the wrong axis — by data source, say, when users think in questions | redraw both boundaries on the question, then re-run the set as a new pair |

Leaving the overlap is the worst option and the default one. The model's choice goes
effectively random per turn, so one user question yields two latencies and two result shapes,
one of them quietly worse — and each individual trace looks fine, so nobody opens a bug.

## The zero-call fork — where a Traffic 0 goes

Zero calls in the window is a finding, not a verdict. Tell the causes apart with the **set**,
not the telemetry — write the turn the item exists for, and see where it routes:

| What you see | Verdict |
|---|---|
| It routes here, and no real turn in the window resembles it | Retire it |
| It routes to a neighbour instead | A routing defect in a retirement costume: fix the description and re-run before deciding anything. Deleting now buries the bug and leaves the neighbour answering a question it was not built for |
| It routes here, and the turn is rare but load-bearing — the compliance export, the incident path | Keep the capability, **move it behind an activation** |

The middle row is the one people get wrong. A tool at zero calls looks dead and is often the
most valuable item in the sweep: something users keep asking for, behind text that never fires.

**Moving an item behind an activation** means putting the tool inside a skill, so its schema
reaches the model only on the turns that activate that skill instead of standing in every
routing decision it will almost never win. That is a disclosure change with its own costs and
its own failure mode — `progressive-tool-disclosure` owns both; this sweep only decides which
items deserve it. It is still a routing change for every survivor, so it goes through the gate.

**Retire in two steps.** Stop exposing it — a routing change, through the gate like any other —
then delete the implementation a window later, once nothing has called it, so a surprise in the
routing run and a surprise in the code can never be the same incident. And **removing one item
is a description change for every survivor**, because its traffic goes somewhere: re-run the
set once it stops being exposed, not only after the code is deleted, or you shipped an
unmeasured routing change under cover of a removal.

Retiring and merging both shrink what a hijacked turn can reach: the control for **ASI02 Tool
Misuse & Exploitation**. Every tool you keep is one somebody has to keep reviewing, and a tool
nobody calls is reviewed by nobody.

## Two skills whose descriptions overlap — pass 3, again

A skill's description is the only thing standing between a turn and the tools inside it. Two
skills claiming the same trigger split those turns the same way two overlapping tools do —
effectively at random, per turn — and one layer up the split costs more, because losing the
turn means the whole tool group behind the loser is never disclosed on it. The failure arrives
disguised: the bug report says "the returns tools are broken", the tools are fine and were
simply never handed to the model on the turns that needed them, and teams debug them for days.

Detection is free, because pass 1 already wrote the subjects down.

- **Same primary subject** → they are one skill. Merge them.
- **Different subjects, one shared trigger phrase** → the phrase belongs to exactly one of
  them, and the sweep's job is to decide which and record the decision:

```
shared phrase   "problem with my delivery"
owner           returns — the outcome asked for is a refund, not a location
loser's clause  order-tracking: "…For an item that already arrived, use returns."
```

A shared phrase goes to whichever skill owns the **outcome** the user asked for, never to the
one that says the noun more often. That clause costs about ten tokens and settles the tie
deterministically instead of leaving it to sampling; `authoring-agent-skills` has the shape to
write it in. Neither description is wrong read alone, which is why only a sweep finds this.

Verify on traffic, not by rereading: a skill still under-firing over the window after the
phrase has been assigned never had an overlap problem — take it back to the zero-call fork.

## Skill bodies — pass 8

A body is what the model reads *after* it activates, and a set with one label per turn
certifies routing to a skill while seeing nothing inside it.

**Grep every body in the catalogue against this sweep's removal list** — every name it merged
away, retired or renamed, tool and skill alike. `authoring-agent-skills` already has each skill
checking its own body against its own declared set, and running that again is not this pass.
This one runs in a different direction, for two reasons that only a sweep produces:

- **A boundary clause names a *skill*, and no declared tool set contains skill names.** The
  per-skill grep is structurally blind to a clause handing ground to a skill you just merged
  away — there is no set for that name to fail to resolve in.
- **The bodies that break are in skills this sweep never opened.** Their files are untouched
  and their owners have no diff to react to, so the per-skill check is not going to be re-run
  on the one occasion it would have fired.

The double-ownership case is `authoring-agent-skills`'s finding until the sweep touches it, and
then it is yours: two skills declared the same tool, you retired one of them, the tool's
implementation went with the skill that carried it, and the survivor's recipes now name a tool
that is gone — in a change whose ticket never mentioned that skill.

**Score each body against `authoring-agent-skills`'s own *Done means* list**, and do not
re-derive a shorter one here. A three-item version of your own passes a body with no entry-point
table and an unbounded listing recipe, and you have then stamped *complete* on something the
authoring skill rejects. Judging one body against that list is also a single-item review by this
sweep's own rule — with one exception, and it is the line about no tool name appearing in two
declared lists. That one needs the whole catalogue, and it is the double-ownership case above,
already this pass's. What the sweep owes each body is the verdict that falls out — *complete* or
*body edit queued* — carried into the deliverable.

A body edit does not move routing and does not spend the gate; what proves it landed is the
sequence cases, the only thing here that watches the model after it activates.

## What the sweep produces

Done means **every inventoried item carries a free-tier score with its denominator and exactly
one verdict**, and **every skill body carries *complete* or *body edit queued***.

| Verdict | Reached from |
|---|---|
| *reviewed, no change* | full marks on the free tier, or the routing run's top band |
| *edit queued behind the gate* | the middle band — one targeted change to the text that is there |
| *rewritten, not edited* | the bottom band, or either half of a re-cut pair: new text drafted from the subject, then A/B'd against the incumbent |
| *merge into `<named item>`* | the merge test |
| *moved behind an activation* | the zero-call fork, third row — which is where the mechanism is written down |
| *retire in two steps* | the zero-call fork, first row |

Six verdicts, and the set is closed. A re-cut pair is two *rewritten* verdicts; a subordination
is *retire in two steps* for the special case plus *edit queued behind the gate* for the
general item that absorbed it. Neither is a seventh.

Every queued edit names, by label, the routing-set turns it must not regress, named before the
new text is written. The deliverable is the confusion diff: every turn that moved, each marked
intended or reverted. A sweep that produces only prose measured nothing.

An item you read and formed no opinion about is not finished. It scores, or the inventory is
wrong.
