# The paid tier — anchors, calibration, and the blind A/B

Open this when the free tier sends an item to the routing run. The free-tier
table and the bands live in `SKILL.md`; this file holds what you only need once
you are spending model calls.

## The paid tier

Bought per item, not per layer. Both rows read the routing run.

| Paid tier | 0 | 1 | 2 |
|---|---|---|---|
| **Reach** — turns labelled for it that it missed | over a third | under a third | under a twentieth |
| **Exclusivity** — turns labelled for a neighbour that it took | over a tenth | some | none |

Reach and exclusivity move against each other, which is the point of scoring
both. A description widened until it catches everything scores 2 on reach and 0
on exclusivity, and its neighbour's reach collapses in the same run. Read them
as a pair, and read them off the confusion table rather than off the hit rate:
the hit rate is one number and it moves for two opposite reasons.

## Arithmetic

| Rows scored | Ships unchanged at | One targeted edit, gated | Structural: rewrite, merge or retire |
|---|---|---|---|
| free tier only (4 rows, /8) | 8 | — | under 8, buy the routing run |
| free + paid (6 rows, /12) | 10–12 | 6–9 | ≤5 |
| any tier with unmeasurable rows dropped | full marks | — | anything less, buy the routing run |

**The free tier has one threshold: full marks.** Whatever the denominator, an
item that does not take every measurable point buys the routing run, which is
why a pre-launch layer scores out of 4 and a zero-traffic item out of 6 without
either being a discount. The denominator rides beside the score because `6/8`,
`6/12` and `4/4` are three different verdicts.

**A row scores *not yet measurable* only when the measurement does not exist
yet** — no traffic window, no log retention that reaches back far enough. A row
you could have measured and did not scores 0. The distinction decides whether
the item is a finding or a gap in your instrumentation, and one sweep later
nobody remembers which it was.

## Calibrating the anchors

The anchors in `SKILL.md` are illustrative. Replace them once, from your own
layer, then freeze them:

1. Dump the free-tier raw numbers for every item — calls in the window,
   first-call correction rate, whether any neighbour shares a trigger phrase.
2. Sort each column and look at where the distribution actually breaks. A layer
   whose median first-call correction rate is 30% does not have a broken median
   item; it has an anchor set copied from somewhere else.
3. Put the 0/1 boundary where the tail starts, and the 1/2 boundary at roughly
   the median. You are ranking items against each other, not against an
   industry number.
4. Write the chosen thresholds into the snapshot file beside the descriptions,
   so the next sweep scores on the same scale. **A threshold changed between
   sweeps invalidates every comparison across them**, and the change is
   invisible in the scores themselves — they just all move.

## Scoring an item, worked

`export_audit_log`, in a layer of 38 tools, one 30-day window:

| Row | Raw | Score | Why |
|---|---|---|---|
| Traffic | 0 calls | 0 | nothing called it |
| Overlap | `search_incidents` shares "incident", not its subject | 1 | shared trigger phrase, different subject |
| Contract consistency | takes `from`/`to` as ISO dates; two neighbours take `since_days` | 0 | disagrees with neighbours on a shared concept |
| First-call recovery | no calls | *not yet measurable* | denominator is zero, so the rate does not exist |

Score **1/6**, denominator 6. That is under threshold, so the routing run is
bought — and it comes back reach 2, exclusivity 2: the turn it exists for routes
straight to it, and it steals nothing. Zero traffic plus perfect reach is the
third row of the zero-call fork in `SKILL.md`: rare but load-bearing. Keep the
capability, move it behind an activation, and fix the date parameter so it
agrees with its neighbours.

The lesson in that worked example is that the free-tier score is a *purchase
decision*, not a verdict. An item scoring 1/6 that turns out to be rare and
load-bearing is a different outcome from an item scoring 1/6 that nobody wants,
and only the paid tier separates them.

## Running the blind A/B

`SKILL.md` says to try reordering the exposed list before writing any candidate
text. Once two candidate rewrites do exist, this is how you decide between them.

1. **Measure the noise floor first.** Temperature zero is not determinism. Run
   the incumbent against the frozen set three times unchanged and take the
   spread as the floor — commonly a turn or two out of thirty, but measure
   yours. A gap inside the floor is not a result.
2. **Strip authorship.** Label them A and B and hand them to whoever runs the
   set, not to whoever wrote them. A reviewer who can tell which one is theirs
   picks theirs, and can then explain why it reads better — an explanation the
   model never sees.
3. **Randomize the candidate's position in the exposed list across runs.** A
   fixed position means you measured position, and handed back a position
   artifact labelled a winner.
4. **Hold everything else identical**: same set, same temperature, same
   neighbours exposed, same model version, recorded in the run header.
5. **Decide on the confusion table, not the hit rate.** A candidate that gains
   overall by absorbing its neighbour's boundary turns has lost — it traded a
   visible defect for an invisible one, and its neighbour's reach will fall in
   the same run.
6. **A gap inside the floor keeps the incumbent.** Shipping a tie spends a gate
   and puts text into production that nobody has watched behave.

Three or more candidates is a sign the item is unclear rather than badly worded:
take it to the merge test or rewrite the description from scratch, rather than
running a tournament whose winner beats two other guesses.
