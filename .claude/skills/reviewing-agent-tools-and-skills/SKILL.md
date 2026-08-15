---
name: reviewing-agent-tools-and-skills
description: Sweep an existing tool and skill layer for routing, contract and skill-body defects, and fix them without moving traffic you did not mean to move.
disable-model-invocation: true
---

# Sweeping a tool and skill layer

**Routing** is which tool or skill the model picks for a turn, and no test suite exercises it:
every tool passes every test it owns while the model reaches for the wrong one on every turn,
and the graph that would show you goes down slowly enough to look seasonal.

This is a sweep, not a review of one tool. **Every check below needs the set** — an item here is
wrong only relative to its neighbours, to its traffic, or to its own text a month ago. A check
that runs against one item sitting alone belongs to a **single-item review**:
`agentic-tool-boundary`'s *Reviewing an existing tool layer* for a tool,
`authoring-agent-skills`'s *Reviewing a skill document* for a skill. Every deferral below goes to
one of those two.

## A description change is a behaviour change

Editing a description ships new behaviour with no code diff to review. Illustrative: widening a
search tool's description to also mention "documents" pulled support turns off the ticket
lookup and into search, which answered from a stale corpus in a confident voice. Nothing threw,
ticket-lookup volume fell ~40%, and nobody read that graph for weeks.

So every description edit passes a **routing gate**: run the frozen set against the layer
before the edit, run it again after, and compare the two **confusion tables** — rows are the
labels, columns are what actually fired — cell by cell.

- **The gate reads a block of the table, not the table.** Its rows and columns are the edited
  item, the items sharing its **primary subject**, and any item either description hands ground
  to by name — three to six items, so six to thirty off-diagonal cells. **Inside the block, every
  cell that moved beyond the noise floor must have been named before the new text was written**
  (floor per `RUBRIC.md`, so a one-turn wobble is not a finding). Unnamed movement blocks the
  ship: a turn arriving from a neighbour you did not predict, and equally a turn leaving,
  **including a gain** — an unclaimed gain is an edit whose mechanism you cannot state, which
  makes the next one a guess too.
- **Outside the block, scan; do not predict.** A forty-item layer has 1560 off-diagonal cells,
  nearly all zero in both runs, and a gate demanding a named prediction for each never passes and
  so gets skipped whole. Movement out there still blocks the ship, but as a finding rather than a
  missed prediction: the edit reached past its subject, so either the index filed the item wrong
  or the text says more than you think it says.
- **A description edit never rides with an edit to its parameters or its handler.** When routing
  moves you must be able to name the one line that moved it.
- **One item per gate run when the items are neighbours.** Unrelated items at opposite ends of
  the catalogue can batch. Edit twelve neighbouring descriptions, route once, and you learned one
  bit.

## Order of operations

| Pass | Costs | What only this pass finds |
|---|---|---|
| 1. Inventory | one log query, one descriptor dump | each item's name, description text, call or activation count over the window, and its **primary subject** — the noun it is actually about, in three words |
| 2. Drift | a `diff` | a description, or a routing-set turn, that changed since the last sweep without a change of yours |
| 3. Overlap → *The merge test*, *Two skills whose descriptions overlap* | reading, bounded by subject; 40 sampled turns and a domain reader per merge test | two items competing for the same turns |
| 4. Contract consistency | reading the shipped schema, bounded by subject | neighbours that disagree about a concept they share |
| 5. Correction rate | one log query — only if calls carry the user goal they belong to | items whose first call gets abandoned more often than the rest |
| 6. Routing run → *The routing set* | a model call per single-label turn, per candidate — and two or more, plus a stub fixture, per sequence turn | reach and exclusivity, measured instead of argued |
| 7. Blind A/B → *Blind A/B* | the routing run again, once per candidate | which of two rewrites is actually better |
| 8. Body sweep → *Skill bodies* | reading, one grep | a body naming a tool this sweep just merged away or retired |

**Passes 1–5 and 8 spend no model calls**, and that is all *free* means here. It is not free of
time: pass 3's merge test spends a domain reader on 40 turns per candidate pair, the scarcest
thing in the sweep — which is what pass 1's subject index is for, keeping the pairs down to the
ones that can actually collide. Passes 6–7 are bought only for what the free tier flagged: thirty
turns per item across forty tools is a 1200-turn set, and most of those forty were fine before
you started. Pass 8 runs last, after every merge and retirement has landed, because it cleans up
after them.

**Pass 1 in full.** Inventory what the framework sends, not what the source says: a parameter
description blanked because a build step dropped it, and a skill whose tools never attached so
activating it changes nothing, both read perfectly in source. Then write each primary subject
down. All pairs in a forty-item layer is 780 comparisons, and the index is the only thing cutting
passes 3 and 4 to a handful — which it does only when its groups are neither one group nor forty:

```
BAD   search_incidents → "searching for things"      the verb: one group of forty, nothing bounded
BAD   list_incident_comments → "incident comments"   too fine: forty groups of one, same result
GOOD  search_incidents, list_incident_comments, close_incident  → "incidents"
      export_audit_log, list_access_grants                      → "audit trail"
```

The subject is the noun the **user's question** is about, not the verb the item performs and not
the system it calls. Three words is the cap because the fourth is nearly always a qualifier that
splits a group in two, and a group of one compares against nothing.

**Pass 2 in full.** Keep every description the layer exposes, and the labelled routing set beside
it, in checked-in snapshot files, and diff both at the start of each sweep. Text you do not
author — anything mounted from a third party — changes under you between releases and arrives as
prompt text the model obeys, so a description that gained a sentence you did not write stops the
sweep: the control for **ASI04 Agentic Supply Chain Vulnerabilities**. The set earns the same
discipline, changing only by a labelled commit naming which turns were added or relabelled and
why — a set edited quietly between the before run and the after run makes both numbers
meaningless.

**On a first sweep there is nothing to diff**, and the honest output is *baseline established*,
not *no drift* — the same confusion as a 0 standing in for an unmeasured row, one level up.
Writing the two snapshot files is pass 2's whole deliverable that run.

**Pass 3 in full** is two sections below: *The merge test*, for two tools answering one question,
and *Two skills whose descriptions overlap*, the same defect one layer up with its own
dispositions.

**Pass 4 in full.** Judging one tool's own arguments, result budget and error text is a
single-item review — `agentic-tool-boundary`'s five questions — and this sweep does not repeat it.
What only the population shows is neighbours disagreeing: one concept spelled `state: "resolved"`
here, `status: "CLOSED"` next door and `incident_state: "done"` in a third tool, so a value read
out of one result is an argument error in the next call; a parameter documented with an example in
one entry and blank in its neighbour; two entries that truncate, one announcing it and one not.
That last is worse than a lone silent truncation: a model that has learned *these results say when
they were cut* reads a missing notice as completeness.

**Compare each item against the items sharing its primary subject, not against the layer** — pass
1's index bounds this pass exactly as it bounds pass 3, on the same arithmetic. A chain crossing
subjects is not caught by reading schemas anyway: that is what pass 6's sequence cases exercise,
where the invented argument shows up as behaviour instead of as a spelling you happened to notice.

**Pass 5 in full.** One query per item: calls of it that were followed by a *different* item being
called for the same user goal. That rate indicts this item's description — it attracted a turn it
could not serve. **A retry of the same item with corrected arguments is not this row.** That is an
argument-contract defect belonging to a single-item review (`agentic-tool-boundary` again), and
scoring the two together sends you off to rewrite a description that was right. The same query is
also the cheapest source of new set turns (`ROUTING-SET.md`).

**Most layers cannot run it.** "The same user goal" is not a field — it needs calls tagged with
the goal or request they belong to, which `llm-cost-observability` owns. Without that tag there is
no denominator, so First-call correction scores *not yet measurable* and the finding is against
your instrumentation, not against the item. Scored 0 instead it reads *this item attracts turns it
cannot serve*, and you go and rewrite a description nobody has measured.

**A layer that has not launched has no window.** Passes 1, 2 and 4 run on text alone — pass 1
minus its call counts — and so does pass 3's detection, since subjects and shared trigger phrases
are in the text. What defers whole is everything reading traffic: pass 3's merge test, pass 5, and
the zero-call fork. Traffic and First-call correction score *not yet measurable*, never 0, so the
free tier scores out of 4.

**Out of 4 is not a band, and a pre-launch 4/4 does not ship unchanged.** The two rows you are
missing are the two that see a description no real turn matches; what is left certifies only that
neighbours do not collide and that the schemas agree. So pre-launch the routing run is not bought
by a low score — it is the only measurement there is, and it runs once across the layer before
launch, sized and read per `ROUTING-SET.md`: bounded by the real phrasing you could find rather
than by turns per item, and scored per subject group, because whether a boundary between
neighbours holds is answerable before launch and whether an item's own traffic arrives is not.

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
for the hand-off one pair at a time, and this row is the scored, population-wide version of it. The
skill's correction row is the cheaper of the two: an activation and its calls are one turn, so it
needs none of pass 5's goal attribution.

**On a launched layer, full marks ships unchanged**, recorded as *reviewed, no change*. Not
editing is a result of the work, not an omission from it — every rewrite is a routing risk you
chose to buy. Anything less buys the routing run, which adds **Reach** (turns labelled for it that
it missed) and **Exclusivity** (turns labelled for a neighbour that it took).

**Record the denominator.** A row whose denominator does not exist scores *not yet measurable*,
never 0, and leaves both numerator and total — so a pre-launch item is scored out of 4 where the
free tier is otherwise out of 8. The two look identical in a summary and mean opposite things: a 0
is a finding about the item, an unmeasured row is a finding about your instrumentation.
`RUBRIC.md` has which is which.

**An item that lost a row goes to the routing run, not to a band.** Full marks on a short
denominator is not the top band wearing a smaller number — the rows you could not score are the
ones that see a description no real turn matches, and that is what the run measures directly. The
pre-launch rule above is this rule with two rows gone instead of one.

→ `RUBRIC.md` — the combined score bands and the verdict each one buys, paid-tier anchors,
calibration, worked scoring, the noise floor, and how to run the blind A/B. Open it the moment an
item misses full marks.

## The routing set

A **routing set** is a list of recorded user turns, each labelled with the item that should have
fired.

**Build it from your logs.** Invented turns are worthless here: you write them with the
description in your head, so they reuse its vocabulary and it routes them by construction. The
turns that break routing are in the user's words — "where's my stuff", "the thing I ordered last
week" — and you will not invent those.

Three include-rules, each defending a score the set would otherwise inflate:

- **Turns that belong to a neighbour, labelled with the neighbour**, or the set measures reach and
  is blind to theft.
- **Turns nothing in the layer should take, labelled *none***, or the cheapest way to raise every
  reach score is to widen every description — and the paid tier becomes gameable by precisely the
  defect it exists to catch. **Two different things wear that label.** One is a goal the layer
  decided not to serve, where recurring volume changes nothing. The other is a goal you labelled
  *none* for want of an item to label it with, where recurring volume is the *capability gap*
  verdict below — and widening a description to absorb it converts an honest *none* into a false
  hit and games the row you just protected. The answer there is a new item, not new words.
- **Turns whose label is an ordered pair**, or the multi-step failure stays invisible: the model
  picks the right first item and then stalls, or skips it and calls the second with an argument it
  invented. One label per turn can never see either.

**Budget the ordered pairs separately.** Telling a stall from a pass means letting the model take
a second step: hand the first call's result back as a stubbed fixture and see whether it
continues. Score a sequence turn from the single call you already made and every stall is recorded
as a pass — you never gave the model the chance to fail. Two or more calls and one stub per
sequence turn, and the three sequence outcomes stay three counts, never one rate.

→ `ROUTING-SET.md` — sourcing, sizing, boundary turns, writing a sequence case, reading the
confusion table, and the pre-launch variant. Open it before the first run.

## Blind A/B

**Reorder the exposed list and re-run the set before you rewrite anything.** Position bias is real
— an item listed first gets picked more — so exposure order is a property you can fix, and fixing
it risks no wording and needs no candidate text at all.

When two candidate rewrites do exist, two people will argue and the argument carries no evidence.
Run both instead — and **decide on the confusion table, not the hit rate**. A candidate that gains
overall by absorbing its neighbour's boundary turns has lost, and the hit rate that crowned it is
the one number that cannot say so: it moves for two opposite reasons. This is the step a correct
experiment still gets wrong, because the winner looks obvious.

The mechanics that keep the comparison honest — authorship withheld, position randomized,
everything else held identical, the floor measured before you believe any gap — are in
`RUBRIC.md`. Run none of them from memory.

## The merge test — two tools that answer the same question

Pull 20 turns that fired tool A and 20 that fired tool B, strip the tool names, and hand the 40 to
someone who knows the domain. **If they cannot say from the turn alone which one should have
fired, the model cannot either** — it has been guessing all along, consistently, in whichever
direction the descriptions lean. Then ask what separates them: **a value the turn always supplies,
or a decision the model would be guessing at?**

```
BAD   search_active_incidents()   search_resolved_incidents()
GOOD  search_incidents(state: "active" | "resolved" | "all")
```

A turn about incidents always says which kind, so the split buys nothing and puts a second item
into every incident routing decision. The corollary is the line to hold while merging: **the
merged tool takes the union of the parameters, not a mode flag.** A parameter selecting which of
the two old behaviours you wanted is a decision, not a value the turn supplies — that pair answers
two different questions, and the fix is two sharper descriptions.

| Disposition | When | What you do |
|---|---|---|
| **Merge** | they differ only by a value the question supplies | one item, one enumerated parameter |
| **Subordinate** | one is a special case of the other | delete the special case; move its case into the general item's description as an example |
| **Re-cut** | the split is on the wrong axis — by data source, say, when users think in questions | redraw both boundaries on the question, then re-run the set as a new pair |

Leaving the overlap is the worst option and the default one. The model's choice goes effectively
random per turn, so one user question yields two latencies and two result shapes, one of them
quietly worse — and each individual trace looks fine, so nobody opens a bug.

## The zero-call fork — where a Traffic 0 goes

Zero calls in the window is a finding, not a verdict. Tell the causes apart with the **set**, not
the telemetry — write the turn the item exists for, and see where it routes:

| What you see | Verdict |
|---|---|
| It routes here, and no real turn in the window resembles it | Retire it |
| It routes to a neighbour instead | A routing defect in a retirement costume: fix the description and re-run before deciding anything. Deleting now buries the bug and leaves the neighbour answering a question it was not built for |
| It routes here, and the turn is rare but load-bearing — the compliance export, the incident path | Keep the capability, **move it behind an activation** |

The middle row is the one people get wrong. A tool at zero calls looks dead and is often the most
valuable item in the sweep: something users keep asking for, behind text that never fires.

**Moving an item behind an activation** means putting the tool inside a skill, so its schema
reaches the model only on the turns that activate that skill instead of standing in every routing
decision it will almost never win. That is a disclosure change with its own costs and its own
failure mode — `progressive-tool-disclosure` owns both; this sweep only decides which items
deserve it. It is still a routing change for every survivor, so it goes through the gate.

**Retire in two steps.** Stop exposing it — a routing change, through the gate like any other —
then delete the implementation a window later, once nothing has called it, so a surprise in the
routing run and a surprise in the code can never be the same incident. And **removing one item is
a description change for every survivor**, because its traffic goes somewhere: re-run the set once
it stops being exposed, not only after the code is deleted, or you shipped an unmeasured routing
change under cover of a removal.

Retiring and merging both shrink what a hijacked turn can reach: the control for **ASI02 Tool
Misuse and Exploitation**. Every tool you keep is one somebody has to keep reviewing, and a tool
nobody calls is reviewed by nobody.

## Two skills whose descriptions overlap

A skill's description is the only thing standing between a turn and the tools inside it. Two
skills claiming the same trigger split those turns the same way two overlapping tools do —
effectively at random, per turn — and one layer up the split costs more, because losing the turn
means the whole tool group behind the loser is never disclosed on it. The failure arrives
disguised: the bug report says "the returns tools are broken", the tools are fine and were simply
never handed to the model on the turns that needed them, and teams debug them for days.

Detection is free, because pass 1 already wrote the subjects down. A shared phrase goes to
whichever skill owns the **outcome** the user asked for, never to the one that says the noun more
often, and the loser records the hand-off:

```
shared phrase   "problem with my delivery"
owner           returns — the outcome asked for is a refund, not a location
loser's clause  order-tracking: "…For an item that already arrived, use returns."
```

That clause costs about ten tokens and settles the tie deterministically instead of leaving it to
sampling; `authoring-agent-skills` has the shape to write it in. Neither description is wrong read
alone, which is why only a sweep finds this.

**The tool dispositions do not carry over.** "One item, one enumerated parameter" means nothing
for two skills, and every disposition here moves tools between disclosure sets — a routing change
per tool, not one for the pair:

| Disposition | When | What you do |
|---|---|---|
| **Merge** | same primary subject | one description, one body, the **union of both declared tool sets** — every tool in it now reaches the model on the survivor's turns and on no others, so the gate runs on the union and the frozen set carries turns labelled for **both** originals |
| **Assign the phrase** | different subjects, one shared trigger phrase | the clause above, written into the loser; both descriptions are queued edits, gated and shipped one at a time |
| **Absorb** | one skill's ground is a special case of the other's | move its tools into the general skill, then retire the skill in two steps — gated as a disclosure change for every tool moved |
| **Re-cut** | both are drawn on the wrong axis — by team or backend, when users ask by outcome | redraw both on the outcome and gate them as a new pair |

**A skill-labelled turn certifies only that the skill fired.** Whether the survivor's body actually
hands over the tools it absorbed is invisible to it, so a merge or an absorption is proven by
**sequence turns** running through the absorbed tools. Without them you shipped a merge whose
second half nobody watched.

Verify on traffic, not by rereading: a skill still under-firing over the window after the phrase
has been assigned never had an overlap problem — take it back to the zero-call fork.

## Skill bodies

A body is what the model reads *after* it activates, and a set with one label per turn certifies
routing to a skill while seeing nothing inside it.

**Grep every body in the catalogue against this sweep's removal list** — every name it merged
away, retired or renamed, tool and skill alike. `authoring-agent-skills` already has each skill
checking its own body against its own declared set, and running that again is not this pass. This
one runs in a different direction, for two reasons only a sweep produces: **a boundary clause
names a *skill*, and no declared tool set contains skill names**, so the per-skill grep is
structurally blind to a clause handing ground to a skill you just merged away — there is no set
for that name to fail to resolve in; and **the bodies that break are in skills this sweep never
opened**, whose files are untouched and whose owners have no diff to react to, so the per-skill
check will not be re-run on the one occasion it would have fired.

The double-ownership case is `authoring-agent-skills`'s finding until the sweep touches it, and
then it is yours: two skills declared the same tool, you retired one of them, the tool's
implementation went with the skill that carried it, and the survivor's recipes now name a tool
that is gone — in a change whose ticket never mentioned that skill.

**Scoring one body's entry points, bounds and failure clauses is a single-item review**, which
this sweep excludes: a shorter list of your own would stamp *complete* on a body that
`authoring-agent-skills`'s checklist rejects. So a body leaves the sweep with *body clean* or *body
edit queued*, the queued one naming the identifier that has to go. Whether it is *finished* is its
owner's call against that checklist, and the queue is what puts it in front of them.

A body edit does not move routing and does not spend the gate; what proves it landed is the
sequence cases, the only thing here that watches the model after it activates.

## What the sweep produces

Done means **every inventoried item carries a free-tier score with its denominator and exactly one
of the six item verdicts**, and **every skill body carries *body clean* or *body edit queued***.

| Verdict | Reached from |
|---|---|
| *reviewed, no change* | full marks on a launched layer's free tier, or the routing run's top band |
| *edit queued behind the gate* | the middle band — one targeted change to the text that is there |
| *rewritten, not edited* | the bottom band, or either half of a re-cut pair: new text drafted from the subject, then A/B'd against the incumbent |
| *merge into `<named item>`* | the merge test, or two skills sharing a primary subject |
| *moved behind an activation* | the zero-call fork, third row — which is where the mechanism is written down |
| *retire in two steps* | the zero-call fork, first row, and the absorbed half of a skill absorption |

Six verdicts per item, and that set is closed. A re-cut pair is two *rewritten* verdicts; a
subordination is *retire in two steps* for the special case plus *edit queued behind the gate* for
the general item that absorbed it. Neither is a seventh — and a body's *body clean* or *body edit
queued* is a second axis, scored per skill, never one of the six.

**One verdict belongs to the layer instead of an item: *capability gap*.** It resolves no item's
score and is reachable from no band, and two passes surface it without either naming an item. Pass
5 finds a goal whose first call was abandoned for a *second* item that did not serve it either, so
the goal left the layer unanswered. Pass 6 finds a *none*-labelled turn that keeps arriving and was
labelled *none* for want of an item, not by decision. Neither is fixed by editing text: the gap
leaves the sweep as a request, and `authoring-agent-tools` is where it goes.

Every queued edit names, by label, the routing-set turns it must not regress, named before the new
text is written. The deliverable is the confusion diff: every turn that moved, each marked intended
or reverted. A sweep that produces only prose measured nothing.

An item you read and formed no opinion about is not finished. It scores, or the inventory is wrong.
