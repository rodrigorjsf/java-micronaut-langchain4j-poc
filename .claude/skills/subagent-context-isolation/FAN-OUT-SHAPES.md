# The fan-out shapes

Reference for [`subagent-context-isolation`](SKILL.md): the five arrangements
children are wired into, what each is for, and the trap each one carries. *When*
a child earns its cost at all, and the two rules that hold across every shape —
combine in code, and a failed branch is a missing field — are in `SKILL.md`.

Call counts below use that skill's convention — count model calls, including the
parent's consumption call — so a child of R round trips costs R + 1 calls, and
the parent pays two of its own on top of whatever the children spend.

## Map — `A ▶ B B B ▶ join`

One child per item, all reading the same kind of thing: screening 200 records,
reading 40 files, checking 12 endpoints against one rule.

**The trap is that width is the bill.** Depth bounds are the ones people write;
width is what actually spends. 200 items at two round trips each is 600 model
calls in the children alone — one user message — and none of it shows up in a
depth limit of 1.
Cap the fan-out itself — a maximum number of children per call, enforced by the
parent before it spawns any — and decide what happens to item 201: dropped
silently is a partial sweep reported as complete, so it comes back as a stated
`truncated` count the parent can put in front of the user.

Map is also the shape where **the items are not independent** more often than the
design assumed. If child 7's answer changes what child 8 should look for, this is
a pipeline wearing a map's clothes, and the join will read as a clean list of
mutually inconsistent findings.

## Best-of-N — `A ▶ B B B ▶ pick`

The same task given to N children, one answer chosen. It is worth its cost only
where the answer is **checkable**: it compiles, the test passes, the value
validates against a schema, the number reconciles.

**Without a deterministic checker it is N times the cost for a coin flip.** "Pick
the best", asked of another model, is one more call whose judgement you can audit
no better than the N it is judging — and a confident wrong answer wins that
comparison against a hedged right one, because fluency is what a picker without a
ground truth is actually ranking.

## Pipeline — `A ▶ B ▶ C`

Stages that transform: extract → verify → format.

**Stage two sees stage one's output and not its evidence**, so a wrong extraction
gets verified against itself and then formatted beautifully. Where the verify
stage genuinely has to re-check the source, it needs the source — which usually
means it was never a separate stage at all.

Every stage boundary is a discard point in its own right, held to the same test
as the first one: if stage three would work better with what stage one read, the
boundary is in the wrong place and those two stages are one agent.

## Critic — `A ▶ B ▶ A`

A child judging an artefact against requirements, its verdict returning to the
parent that produced it.

**It earns its call only when it sees something the parent cannot** — which means
it must not receive the reasoning that produced the artefact. Hand a critic the
argument for the thing it is reviewing and it agrees with it; the parent pays a
round trip to hear its own conclusion back in a second voice. Give it the
artefact and the requirements, and nothing else.

## Router — `A ▶ (B | C | D)`

Mutually exclusive branches: one of several specialists handles the turn.

**The routing decision is itself a model call.** Route + child + consume is three
calls against the one or two of a single agent that simply holds all three tool
sets. A router pays for itself when the branches differ in something other than
tools — a different system prompt, a different model tier, a different data
boundary — and not when the only difference is which four tools are in scope.
That case is tool disclosure, not delegation.
