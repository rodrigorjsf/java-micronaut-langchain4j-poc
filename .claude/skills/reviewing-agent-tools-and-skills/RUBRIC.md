# The bands, the paid tier, the noise floor, and the blind A/B

Open this when the free tier sends an item to the routing run. The free-tier
table lives in `SKILL.md`; this file holds the bands and everything else you
only need once you are spending model calls.

## The paid tier

Bought per item, not per layer. Both rows read the routing run.

| Paid tier | 0 | 1 | 2 |
|---|---|---|---|
| **Reach** — turns labelled for it that it missed | over a third | under a third | under a twentieth |
| **Exclusivity** — turns labelled for a neighbour that it took | over a tenth | some | none |

Reach and exclusivity move against each other, which is the point of scoring
both. A description widened until it catches everything scores 2 on reach and 0
on exclusivity, and its neighbour's reach collapses in the same run — so score
the neighbour in the same run, and read both rows off the confusion table's
cells rather than off any total the run reports.

## The bands

Both tiers scored is six rows out of 12. Each band buys exactly one of the six
**item** verdicts `SKILL.md` closes on. The seventh, *capability gap*, belongs to
the layer and is reachable from no band:

| Band | Score | Verdict it buys |
|---|---|---|
| Top | 10–12 | *reviewed, no change* |
| Middle | 6–9 | *edit queued behind the gate* |
| Bottom | ≤5 | structural: *rewritten, not edited*, *merge into `<named item>`*, *moved behind an activation*, or *retire in two steps* |

The bands assume six rows. With a row dropped as *not yet measurable*, scale
them to the denominator you actually have before reading off a band — except
pre-launch, where both traffic-fed rows are missing at once and `SKILL.md` sends
the whole layer to the routing run rather than to a band.

**A row scores *not yet measurable* only when its denominator does not exist** —
no traffic window, no log retention reaching back far enough, no calls to divide
by, or no goal attribution to correlate on, which is the usual reason First-call
correction is unscoreable on a launched layer (`llm-cost-observability` owns the
tagging that fixes it). A row you could have computed from data you already hold
and did not scores 0. The distinction decides whether the item is a finding or a
gap in your instrumentation, and one sweep later nobody remembers which it was.

## Calibrating the anchors

The anchors in `SKILL.md` are illustrative. Replace them once, from your own
layer, then freeze them:

1. Dump the free-tier raw numbers for every item — calls in the window beside
   the item's primary subject, its first-call correction rate from pass 5,
   whether any neighbour shares a trigger phrase.
2. Sort each column and look at where the distribution actually breaks. Sort
   **calls within each primary-subject group**, not across the layer: an item is
   over- or under-called only relative to the items answering about the same
   noun, and a layer-wide sort buries a starved item behind a popular subject.
   Correction rate sorts layer-wide. A layer whose median correction rate is 30%
   does not have a broken median item; it has an anchor set copied from
   somewhere else.
3. Put the 0/1 boundary where the tail starts, and the 1/2 boundary at roughly
   the median of whichever group you sorted. You are ranking items against each
   other, not against an industry number.
4. Write the chosen thresholds into the snapshot file beside the descriptions,
   so the next sweep scores on the same scale. **A threshold changed between
   sweeps invalidates every comparison across them**, and the change is
   invisible in the scores themselves — they just all move.

## Scoring an item, worked

`export_audit_log`, in a layer of 38 tools, one 30-day window:

| Row | Raw | Score | Why |
|---|---|---|---|
| Traffic | 0 calls; two peers share its "audit trail" subject and are called daily | 0 | nothing called it |
| Overlap | `search_incidents` shares "incident", not its subject | 1 | shared trigger phrase, different subject |
| Contract consistency | takes `from`/`to` as ISO dates; two neighbours take `since_days` | 0 | disagrees with neighbours on a shared concept |
| First-call correction | no calls | *not yet measurable* | denominator is zero, so the rate does not exist |

Score **1/6**, denominator 6. That is under threshold, so the routing run is
bought — and it comes back reach 2, exclusivity 2: the turn it exists for routes
straight to it, and it steals nothing. Zero traffic plus perfect reach is the
third row of the zero-call fork in `SKILL.md`: rare but load-bearing. Verdict
***moved behind an activation*** — put the tool inside a skill so its schema
reaches the model only after that skill activates, which is the fork's third row
in full and `progressive-tool-disclosure`'s subject; then fix the date parameter
so it agrees with its neighbours.

The lesson in that worked example is that the free-tier score is a *purchase
decision*, not a verdict. An item scoring 1/6 that turns out to be rare and
load-bearing is a different outcome from an item scoring 1/6 that nobody wants,
and only the paid tier separates them.

## The noise floor

**Measure it before you believe any gap.** Temperature zero is not determinism.
Run the incumbent against the frozen set three times unchanged, and take the
**largest movement any single confusion cell shows across the three** as the
floor — commonly a turn or two out of thirty, but measure yours. Per cell, not
summed across the table: a floor of 2 means one cell moving by 2 is noise and
ten cells each moving by 1 is not.

Both the routing gate in `SKILL.md` and the A/B below read against it: movement
inside the floor is not movement, and a gate that blocks on it never passes.
Re-measure whenever the model version changes, and record the floor in the run
header beside it.

## Running the blind A/B

`SKILL.md` says to try reordering the exposed list before writing any candidate
text. Once two candidate rewrites do exist, this is how you decide between them.

1. **Strip authorship.** Label them A and B and hand them to whoever runs the
   set, not to whoever wrote them. A reviewer who can tell which one is theirs
   picks theirs, and can then explain why it reads better — an explanation the
   model never sees.
2. **Randomize the candidate's position in the exposed list across runs.** A
   fixed position means you measured position, and handed back a position
   artifact labelled a winner.
3. **Hold everything else identical**: same set, same temperature, same
   neighbours exposed, same model version, recorded in the run header.
4. **Decide on the confusion table, not the hit rate.** A candidate that gains
   overall by absorbing its neighbour's boundary turns has lost — it traded a
   visible defect for an invisible one, and its neighbour's reach will fall in
   the same run.
5. **A gap inside the floor keeps the incumbent.** Shipping a tie spends a gate
   and puts text into production that nobody has watched behave.

Three or more candidates is a sign the item is unclear rather than badly worded:
take it to the merge test or rewrite the description from scratch, rather than
running a tournament whose winner beats two other guesses.
