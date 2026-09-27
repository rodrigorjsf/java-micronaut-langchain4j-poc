# This repository's suite, as the illustration

Open this when working on the committed query set of the repository this skill was
written in, or when the difference between a negative row and a pinned probe is not
yet clear. It illustrates `SKILL.md`, *The negative half is the load-bearing half*,
and *What to assert*, assertions 2 and 3.

This repository's own suite is the illustration, and it is an illustration of the
gap: the two committed negatives in `src/test/resources/evals/retrieval-queries.json`
(`neg-receita-bolo`, `neg-script-python`) are a cake recipe and a request to write a Python script, both plainly out of domain. What such rows
are worth is in the measurement recorded in `rag/SkillAwareQueryRouter.java`'s
javadoc — a third out-of-domain question, a football result, scored **0.7342**
against a relevant question's **0.7299**. Even the far negatives sat inside the
relevant band. Those two rows are the floor; that suite has no near-miss half yet.

**That football question is in the suite. It is a pinned probe, not a negative
row** — and the distinction is the point, not a technicality. It sits at
`KnowledgeBaseTest.theScoreDistributionsOverlap`, which is
assertion 3 of `SKILL.md`, *What to assert*: a second retriever at `minScore` 0.0 and k of 1, asserting the
irrelevant score stays **above** the relevant one. It could not have been made a
negative row instead. The shipped gate is `agentic.rag.min-score: 0.72`
(`application.yml:562`), 0.7342 clears it, and a row asserting emptiness through the
shipped path would have been red the day it was written. The two questions that
*are* rows score 0.7058 and 0.6826 — under the gate, which is the only reason they
can assert anything.

So the two assertions measure different things and neither substitutes for the
other. **Assertion 2 can only ever report *above the gate or below it*, and
assertion 3 exists because the numbers that decide whether any gate can work live
below the gate, where assertion 2 is blind** — and here the worst irrelevant
question turned out to sit above it. Read as a missing negative row, this
measurement says "somebody forgot to add a test". Read correctly, it says no
threshold on this corpus separates relevant from irrelevant, which is the reason
`SkillAwareQueryRouter` exists at all. A near-miss half is still owed; a fourth far
negative is not what is missing.

One further gap in the same file, since it is the reason the near-miss half is not a
detail: `anUnrelatedQuestionRetrievesNothing` calls the retriever directly, so
neither committed negative carries a `routing_input` through the shipped router at
all.
