# Overlapping skills and skill bodies

Open this when two skill descriptions scored Overlap 0 or 1 against each other,
and at pass 8, once every merge and retirement has landed.

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

**Every disposition here leaves two verdicts, and the survivor is the one that gets forgotten.** The
half that disappears is easy to remember. The half that stays ships an unscored change: a merge or
an absorption grows its declared tool set, so its disclosure moved for every tool it took on. An
absorption's survivor leaves as *edit queued behind the gate* whether or not a word of its
description changed; a merge's survivor leaves as *rewritten, not edited*, its description written
against the union rather than edited. Score it, or the gate runs on a tool set nobody wrote down.

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
