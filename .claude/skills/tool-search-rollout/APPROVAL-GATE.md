# Step 3 — the approval gate

Reference for [`tool-search-rollout`](SKILL.md), Step 3. Open it when the
decisions are made and before any file is edited: everything the user is shown, in
one place, and what counts as approval.

## Step 3 — The approval gate

Nothing is edited before this. The user is shown, in one place:

1. **The census, whole** — including every `unknown` row, which is where the
   questions for other people are.
2. **The strategy choice**, the countable inputs behind it, and the one input
   whose change would flip it.
3. **The search tool's configuration as literal strings** — tool name, tool
   description, argument name, argument description, `maxResults`, `minScore` —
   with the framework default beside each one you are changing. Under semantic
   search `minScore` is not a builder setting: `VectorToolSearchStrategy.Builder`
   has no `minScore` setter `[verified — javap on langchain4j 1.18.1]`
   `[sourced — source at tag 1.20.1]`, so its floor is a fixed `0.0` that filters nothing, and a real
   floor ships as a constant in your own `ToolSearchStrategy` —
   `STRATEGY-AND-DESCRIPTIONS.md`, *What semantic search actually does*.
4. **Every tool name and description that changes, before and after, in full.**
5. *(Conditional — only where some census row's `wording` is not `ours`.)*
   **Every census row whose `wording` is not `ours`**, and the move proposed for
   each one, named from the four in `STRATEGY-AND-DESCRIPTIONS.md` and in that
   file's order. A row left with no move is a tool this pass is choosing to make
   unfindable, and it goes to the gate in those words.
6. **The framework version this ships against, pinned exactly**, with the search
   SPI's experimental status named and the short list an upgrade has to re-read
   before it lands.
7. **The disclosure-lifetime observation** — how long one search's result stays
   in front of the model — the serialized specifications it came from, and the
   `maxResults` that follows from it. Where the measurement was not affordable,
   this item reads **defaulted, not observed**: it carries the conservative
   assumption it defaulted to, the single-step tool count that sized
   `maxResults`, and the seam whose absence made the measurement unaffordable.
   The one thing this item may never do is present a defaulted number in the
   grammar of a measured one.
8. **Every tool proposed for `ALWAYS_VISIBLE`**, with the reason it must stay
   visible, and two things that reason has to survive. First: **none of them is a
   tool the scope was meant to hide** — a marked tool sits outside anything the
   per-caller filter of layer 1 can narrow. Check this item against the census's
   `principals` column, row by row, and say at the gate that you did. Second: for
   each marked tool, the `reach` cell of the provider it arrives through,
   **including the expression where that answer is computed** — what is being
   approved is a claim about every state that provider can reach, not the one it
   is in this week. `SCOPED-TOOLS.md`, layer 1, and
   `STRATEGY-AND-DESCRIPTIONS.md`, *Always-visible tools*, carry why.
9. *(Conditional — the per-caller filter half exists only where brief 2 found a
   model.)* **The permission finding**, and the per-caller filter proposed on top
   of it. Where no model was found, this item **is** the negative finding — the
   patterns searched, the hit counts, the walk from entry point to executing
   tool, and the trigger that should start a model — together with the plan
   saying in those words that this pass added no scoping.
10. *(Conditional — only where brief 2 found a permission model.)* **The
    execution guardrail**: where it attaches, what it reads, what it
    returns when it refuses.
11. *(Conditional — only where the pass proposes a cache, which in practice
    follows the per-caller filter.)* **Any cache proposed anywhere on the search
    path**, with its key written out
    in full and every caller-varying input in that key identified. A cache is a
    change to who sees what, and it is approved as one.
12. **The query set** the rewrite will be measured against, written down before
    the edits it will judge — **including its negative rows**, counted
    separately: the turns labelled to a *neighbouring* tool, and the turns
    labelled *no tool should serve this*. A set that is all positive rows
    measures recall alone, and recall is maximised by widening every
    description, which is the rewrite's own failure mode. Approving a
    positive-only set is approving the failure mode as the evidence.
13. **The standing retrieval check** — what it asserts for each class of `reach`,
    where it runs, and what it costs on every build. Plus the number this item
    is really asking for: the check cannot land while a searchable tool has no
    query-set row, so say how many rows had to be **written by hand** rather
    than harvested from real turns. Approving this item is approving those
    sentences, and it is approving a check whose scope is **every searchable
    tool** — scoped instead to "the tools that already have rows", it restores
    exactly the hole it was built to close.
14. **The instrumentation** shipping with the change: the fields recorded per
    search and per tool call — the set is in `STRATEGY-AND-DESCRIPTIONS.md`,
    *What the record shows once it ships* — where they are written, how long they
    are kept, and what is redacted before they get there.
15. **The rollback** — what reverting looks like, and what the layer behaves like
    with search switched back off.
16. **What this pass will not do**, named: the `unknown` rows it cannot resolve,
    the dynamic providers it leaves visible, the object-level permission
    questions it cannot answer at discovery time.

**Four of those sixteen are conditional — three can disappear outright and one
appears in one of two forms — and a run that skips one is still a complete
gate.** Items 9, 10 and 11 turn on whether brief 2 found a permission
model — 10 and 11 disappear without one, and 9 changes into its negative branch
rather than vanishing. Item 5 turns on whether some census row's `wording` is not
`ours`. A gate showing fewer than sixteen because a condition did not fire is
finished, and it names which items and which condition. A gate missing an *unconditional* item is
unfinished. Name the skipped items rather than presenting all sixteen every time
with *n/a* in four of them, which trains everyone reading the gate to skim past
exactly the rows carrying the permission claims.

**The plan is a diff proposal, not a summary.** A gate that reads "rewrite tool
descriptions for retrievability" approves nothing: the user is approving text a
model reads on every turn to decide what this system does, and they can only
approve text they have read. Editing a description is shipping behaviour with no
code diff — `reviewing-agent-tools-and-skills` owns that argument and the gate
that goes with it, and item 4 above is where it lands in this pass.

Approval is per item, not for the whole block. Item 4 is routinely approved with
three of its rewrites sent back, and that is the gate working.
