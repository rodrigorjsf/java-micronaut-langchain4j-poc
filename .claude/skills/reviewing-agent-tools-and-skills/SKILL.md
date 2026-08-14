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

Editing a description ships new behaviour with no code diff to review. Illustrative: a team
widened a search tool's description to also mention "documents"; support turns that had been
going to the ticket lookup went to search, which answered from a stale corpus in a confident
voice. Nothing threw, ticket-lookup volume fell ~40%, and no one read that graph for weeks.

- **Every description edit passes a routing gate before it ships.**
- **A description edit never rides with an edit to its parameters or its handler.** When
  routing moves you must be able to name the one line that moved it.
- **One item per gate run when the items are neighbours.** Unrelated items at opposite ends of
  the catalogue can batch. Edit twelve descriptions, route once, and you learned one bit.

## Order of operations

| Pass | Costs | What only this pass finds |
|---|---|---|
| 1. Inventory | one log query, one descriptor dump | each item's name, description text, call or activation count over a fixed window, and its **primary subject** — the noun it is actually about, in three words |
| 2. Drift check | a `diff` | a description, or a routing-set turn, that changed since the last sweep without a change of yours |
| 3. Overlap | reading, bounded by subject | two items competing for the same turns |
| 4. Contract consistency | reading the shipped schema | neighbours that disagree about a concept they share |
| 5. Log metrics | one log query | items whose first call is corrected more often than the rest |
| 6. Routing run | one model call per turn, per candidate | reach and exclusivity, measured instead of argued |
| 7. Blind A/B | the routing run again, once per candidate | which of two rewrites is actually better |

Passes 1–5 are the free tier and run against **everything**. Passes 6–7 run only against what
the free tier flagged. Forty tools against a 30-turn set is 1200 model calls per run — and
most of those forty were fine before you started.

**Inventory what the framework sends, not what the source says**: a parameter description
blanked because a build step dropped it, and a skill whose tools never attached so activating
it changes nothing, both read perfectly in source. Then **compare only items whose primary
subjects share a noun** — all pairs in a forty-item layer is 780 comparisons, the subject index
cuts it to a handful, and that is what makes the free tier affordable across the whole set.

**A layer that has not launched has no window.** Passes 1–4 and the routing run still work.
Pass 5, the zero-call fork and the 20-turn merge test need traffic: defer them and score their
rubric rows *not yet measurable*, never 0.

**Pass 2 in full.** Keep every description the layer exposes, and the labelled routing set
beside it, in checked-in snapshot files, and diff both at the start of each sweep. Text you do
not author — anything mounted from a third party — changes under you between releases and
arrives as prompt text the model obeys, so a description that gained a sentence you did not
write stops the sweep: the control for **ASI04 Agentic Supply Chain Vulnerabilities**. The set
earns the same discipline, changing only by a labelled commit naming which turns were added or
relabelled and why — a set edited quietly between the before run and the after run makes both
numbers meaningless.

**Pass 4 in full.** Judging one tool's own arguments, result budget and error text is a
single-item review and this sweep does not repeat it. What only the population shows is
neighbours disagreeing — one concept spelled `state: "resolved"` here, `status: "CLOSED"` next
door and `incident_state: "done"` in a third tool, so a value read out of one result is an
argument error in the next call; a parameter documented with an example in one entry and blank
in its neighbour; two entries that truncate, one announcing it and one not. That last beats a
lone silent truncation for damage: a model that has learned *these results say when they were
cut* reads a missing notice as completeness.

## The rubric

Score every inventoried item on the free tier. Anchors are illustrative — calibrate them once
from your own distribution and freeze them, or scores stop comparing across sweeps.

| Free tier | 0 | 1 | 2 |
|---|---|---|---|
| **Traffic** — calls or activations in the window | zero | called, but far under how often its subject comes up in traffic | called at a rate that tracks its subject |
| **Overlap** — against its nearest neighbour | shares its primary subject | shares a trigger phrase | no neighbour shares a trigger phrase |
| **Contract consistency** — against its neighbours | disagrees with one on a shared concept | consistent, but undocumented | consistent and documented |
| **First-call recovery** — corrected retries of the same item | over a quarter of calls | between a twentieth and a quarter | under a twentieth |

**8/8 ships unchanged**, recorded as *reviewed, no change*. Not editing is a result of the
work, not an omission from it — every rewrite is a routing risk you chose to buy. Under 8, buy
the routing run, which adds **Reach** and **Exclusivity** for 12 in total: **10–12** reviewed,
no change; **6–9** one targeted edit, gated; **≤5** it is not an editing problem — rewrite the
description from scratch, merge the item into a neighbour, or retire it.

**Record the denominator.** An unmeasurable row scores *not yet measurable* and leaves both
numerator and total, so a pre-launch layer scores out of 4 and ships unchanged at 4/4. A 0 and
an unmeasured row look identical in a summary and mean opposite things.

→ `RUBRIC.md` — paid-tier anchors, calibration, worked scoring, and how to run the blind A/B.
Open it when the free tier sends an item to the routing run.

## The routing set

A **routing set** is a list of recorded user turns, each labelled with the item that should
have fired. **Hit rate** is the share of turns that reached their label.

**Build it from your logs.** Invented turns are worthless here: you write them with the
description in your head, so they reuse its vocabulary and it routes them by construction. The
turns that actually break routing are in the user's words — "where's my stuff", "the thing I
ordered last week" — and you will not invent those. **Include turns that belong to a
neighbour, labelled with the neighbour**, or the set measures reach and is blind to theft.
**Include turns whose label is an ordered pair**, or the multi-step failure stays invisible:
the model picks the right first item and then stalls, or skips it and calls the second with an
argument it invented. One label per turn can never see either.

**Report the confusion between items, never a single accuracy number.** An aggregate hides the
swap where the edited item gained exactly what its neighbour lost — the most common outcome of
a description edit, and the thing you are gating against.

→ `ROUTING-SET.md` — sourcing, sizing, boundary turns, writing a sequence case, and the
pre-launch variant. Open it before the first run.

## Blind A/B on two candidate descriptions

**Reorder the exposed list and re-run the set before you rewrite anything.** Position bias is
real — an item listed first gets picked more — so exposure order is a property you can fix,
and fixing it risks no wording and needs no candidate text at all.

When two candidate rewrites do exist, two people will argue and the argument carries no
evidence. Run both instead: labelled A and B with authorship withheld from whoever runs the
set, the candidate's position randomized across runs, everything else held identical, and the
noise floor measured before you believe any gap. Procedure in `RUBRIC.md`.

## Two tools that answer the same question

The test: pull 20 turns that fired tool A and 20 that fired tool B, strip the tool names, and
hand them to someone who knows the domain. **If they cannot say from the turn alone which one
should have fired, the model cannot either** — it has been guessing all along, consistently,
in whichever direction the descriptions lean. Then ask what separates them: **a value the turn
always supplies, or a decision the model would be guessing at?**

```
BAD   search_active_incidents()   search_resolved_incidents()
GOOD  search_incidents(state: "active" | "resolved" | "all")
```

A turn about incidents always says which kind, so the split buys nothing and puts a second
item into every incident routing decision. The corollary is the line to hold while merging:
**the merged tool takes the union of the parameters, not a mode flag.** A parameter that
selects which of the two old behaviours you wanted is a decision, not a value the turn
supplies — that pair was answering two questions, and the fix is two sharper descriptions.

| Disposition | When | What you do |
|---|---|---|
| **Merge** | they differ only by a value the question supplies | one item, one enumerated parameter |
| **Subordinate** | one is a special case of the other | delete the special case; move its case into the general item's description as an example |
| **Re-cut** | the split is on the wrong axis — by data source, say, when users think in questions | redraw both boundaries on the question, then re-run the set as a new pair |

Leaving the overlap is the worst option and the default one. The model's choice goes
effectively random per turn, so one user question yields two latencies and two result shapes,
one of them quietly worse — and each individual trace looks fine, so nobody opens a bug.

## A tool nobody calls

Zero calls in the window is a finding, not a verdict. Tell the causes apart with the **set**,
not the telemetry — write the turn the item exists for, and see where it routes:

| What you see | Verdict |
|---|---|
| It routes here, and no real turn in the window resembles it | Retire it |
| It routes to a neighbour instead | A routing defect in a retirement costume: fix the description and re-run before deciding anything. Deleting now buries the bug and leaves the neighbour answering a question it was not built for |
| It routes here, and the turn is rare but load-bearing — the compliance export, the incident path | Keep the capability and move it behind an activation, so it stops standing in every routing decision it will almost never win |

The middle row is the one people get wrong. A tool at zero calls looks dead and is often the
most valuable item in the sweep: something users keep asking for, behind text that never fires.

**Retire in two steps.** Stop exposing it — a routing change, through the gate like any other
— then delete the implementation a window later, once nothing has called it, so a surprise in
the routing run and a surprise in the code can never be the same incident. And **removing one
item is a description change for every survivor**, because its traffic goes somewhere: re-run
the set once it stops being exposed, not only after the code is deleted, or you shipped an
unmeasured routing change under cover of a removal.

Retiring and merging both shrink what a hijacked turn can reach: the control for **ASI02 Tool
Misuse & Exploitation**. Every tool you keep is one somebody has to keep reviewing, and a tool
nobody calls is reviewed by nobody.

## Two skills whose descriptions overlap

A skill's description is the only thing standing between a turn and the tools inside it. When
two claim the same trigger, the model picks whichever matches first and keeps picking it. The
loser does not fire less — it stops firing, and its whole tool group becomes unreachable. The
failure arrives disguised: the bug report says "the returns tools are broken", the tools are
fine and simply never disclosed, and teams debug them for days.

Detection is free — write each skill's primary subject out in three words. **Same primary
subject** → they are one skill, so merge them. **Different subjects, one shared trigger
phrase** → the phrase lives in exactly one description, and goes to whichever skill owns the
*outcome* the user asked for, not to the one that says the noun more often. Both of these pass
a single-item review, and still collide on one word:

```
BAD   order-tracking: "Where a shipment is and when it arrives. Use when the user
                       asks about a delivery."
      returns:        "Send an item back for a refund. Use when the user is
                       unhappy with a delivery."

GOOD  order-tracking: "…Use for an order that has not arrived yet. Once it has
                       arrived, use returns."
      returns:        "…Use once the item is in the user's hands. For an order
                       still in transit, use order-tracking."
```

"there's a problem with my delivery" is a coin flip against the BAD pair, and neither
description is wrong read alone — which is why only a sweep finds this. The contrast clause
costs about ten tokens and settles the tie deterministically instead of leaving it to
sampling. Verify on traffic, not by rereading: a starved skill still at zero over the next
window never had an overlap problem — take it back to the zero-call fork.

## Sweeping the skill bodies

A body is what the model reads *after* it activates, and a set with one label per turn
certifies routing to a skill while seeing nothing inside it. Sweep for the two findings only
the population produces — one of which this sweep just caused.

**Your own merges and retirements broke other bodies.** Grep every body for every name this
sweep removed or renamed, tool and skill alike. A boundary clause pointing at a skill that no
longer exists sends the model somewhere unreachable; a recipe naming a deleted tool produces a
hallucinated-tool error the model improvises around, and nothing in the logs says the body is
wrong. And **a tool disclosed by two bodies is owned by neither**: merge or retire one of those
skills and the other's recipes lose a tool they name, in a change that never mentioned them.

Then one scored count per body — does it name its tools by the exact identifier the model must
emit, does it say what it does **not** cover, does it say what to do when one of its tools
fails. Verdict: *complete* at three of three, otherwise *body edit queued*. A body edit does
not move routing and does not spend the gate; what proves it landed is the sequence cases, the
only thing here that watches the model after it activates.

## What the sweep produces

Done means **every inventoried item carries a free-tier score with its denominator and exactly
one verdict** — *reviewed, no change* · *edit queued behind the gate* · *merge into `<named
item>`* · *retire in two steps* — and **every skill body carries *complete* or *body edit
queued***. Every queued edit names, by label, the routing-set turns it must not regress, named
before the new text is written. The deliverable is the confusion diff: every turn that moved,
each marked intended or reverted. A sweep that produces only prose measured nothing.

An item you read and formed no opinion about is not finished. It scores, or the inventory is
wrong.
