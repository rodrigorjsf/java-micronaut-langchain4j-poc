# The removal half, gate by gate

Open this while working a block through the five steps in `SKILL.md`, *The removal
half*, and whenever step 2 fails: why each gate sits where it does, and what a
failed proof sends you to instead of deleting anyway.

**Step 0 is first because a list written afterwards is graded against itself.**
Write the document first and the questions come out of its own vocabulary, so
every one of them passes and the suite proves nothing. Draw the questions from
real turns wherever logs have them — where the sentences come from is one
priority-ordered list, owned by `corpus-retrieval-tests`, and this skill points at
it rather than printing a second one.

**Step 2's gate says "through the router" for a reason.** A proof run with the
router bypassed proves the chunk exists. It does not prove the turn reaches it,
which is the only thing the deletion depends on.

**Step 3 is its own commit** because a deletion bundled with the document cannot
be reverted without also reverting the corpus, and the first production surprise
wants exactly that revert and nothing else.

**What stays behind is at most a capability line** — one sentence saying the
capability exists so the model knows to look — and **never a restatement of a
fact**. A pointer carrying one fact is the two-answers drift at one-line scale.

### When step 2 fails, which is not "delete anyway"

A query that misses has **four layers** that can be at fault — corpus, chunk
boundary, vocabulary, threshold-or-router — and, checked ahead of all four, the row
itself, which is not a layer. Which one it was is diagnosed by
`corpus-retrieval-tests`, and that diagnosis is not re-derived here; that skill is
one a model cannot load either, so the diagnosis happens when you ask the user to
run corpus-retrieval-tests, which they invoke by name. What this skill owns is what
the migration does with each verdict:

| The diagnosis | The migration |
|---|---|
| the corpus layer — the fact is in no document, and the probe finds it only in the prompt | the document was never written for this fact, so this is a migration that did not happen rather than a test failure — back to step 1. `corpus-retrieval-tests` hands that verdict back to this skill by name |
| the document is at fault — chunk boundary or vocabulary | back to step 1. The block stays in the prompt meanwhile and the branch does not merge half-done |
| the retrieval layer is at fault — threshold or router — **and a signal for this class of turn exists**, so the layer had something to route on and got it wrong | **stop migrating** and fix the layer first (`retrieval-that-earns-its-place`). Every further block loaded onto a layer that cannot route multiplies one defect |
| the acceptance row was wrong | fix the row, re-run, **and record the edit in the ledger** — a row edited until it passed is honest only when the edit is visible |
| the router declined and **no signal for this class of turn exists** — nothing in the turn distinguishes the ones that need this block from the ones that do not | return the block to the **stays** side of the test above and record why. This is a result, not a failure: question 2 answered "yes" on paper and "no" in practice. The layer is not at fault and there is nothing to fix, which is what separates this row from the one above |
