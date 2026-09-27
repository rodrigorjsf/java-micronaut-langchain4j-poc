# The merge test and the zero-call fork

Open this when a tool scored Overlap 0 against a neighbour, or Traffic 0: the two
structural verdicts a tool can reach before any description is rewritten.

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

A turn about incidents always says which kind, so the split buys nothing and puts a second item into
every incident routing decision. When the answer is *a decision* instead, the pair is not a merge
candidate at all: **the merged tool takes the union of the parameters, never a mode flag**, and a
flag choosing which of the two old behaviours you wanted is that decision wearing a parameter.
*Why* a tool that hides a decision gets split is `agentic-tool-boundary`'s *Split a tool when it
hides a decision*; finding the hidden decision while the tool is still on paper is
`authoring-agent-tools` §4. The sweep's own part is smaller — the merge stops here, and the fix is
two sharper descriptions.

| Disposition | When | What you do |
|---|---|---|
| **Merge** | they differ only by a value the question supplies | one item, one enumerated parameter, its description written against the union rather than edited — which is why the survivor scores *rewritten, not edited* and the half that goes scores *merge into `<named item>`* |
| **Subordinate** | one is a special case of the other | withdraw the special case **in two steps**, as under *The zero-call fork* — deleting it outright ships an unmeasured routing change under cover of a removal; move its case into the general item's description as an example |
| **Re-cut** | the split is on the wrong axis — by data source, say, when users think in questions | redraw both boundaries on the question, then re-run the set as a new pair |

Leaving the overlap is the worst option and the default one. The model's choice goes effectively
random per turn, so one user question yields two latencies and two result shapes, one of them
quietly worse — and each individual trace looks fine, so nobody opens a bug.

## The zero-call fork — where a Traffic 0 goes

Zero calls in a window sized per pass 1 is a finding, not a verdict — and against a window too short
for the item it is not even that. Tell the causes apart with the **routing set**, not the telemetry:
write the turn the item exists for, and see where it routes.

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
a description change for every survivor**, because its traffic goes somewhere: re-run the routing
set once it stops being exposed, not only after the code is deleted, or you shipped an unmeasured
routing change under cover of a removal.

Retiring and merging both shrink what a hijacked turn can reach: the control for **ASI02 Tool
Misuse and Exploitation**. Every tool you keep is one somebody has to keep reviewing, and a tool
nobody calls is reviewed by nobody.
