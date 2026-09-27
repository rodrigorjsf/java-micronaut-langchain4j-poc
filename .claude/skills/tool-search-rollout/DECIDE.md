# Step 2 — the four decisions

Reference for [`tool-search-rollout`](SKILL.md), Step 2. Open it once both
returns of Step 1 have landed and the `principals` column is complete.

## Step 2 — Decide

Four decisions, in this order. The first is settled here; the other three each
have a sibling that carries them, and the fourth exists only on the branch where
brief 2 found a permission model — on the other branch the decision is the
recorded negative, which is what gate item 9 asks for.

**Is the rollout still viable?** Count the census rows by `reach`. Resolve every
`unknown` first: an unsurveyed provider counted as nothing is a layer that looks
all-dynamic because nobody looked, and the stop below would then be a verdict the
evidence has not earned. Once none are left, if nothing is
`static` or `provider`, stop — see `SKILL.md`, *When not to run this pass*. If some are, the
rollout covers those and the dynamic ones stay permanently visible; say so
explicitly in the plan rather than letting the reader assume search covers
everything.

**How long one search's disclosure lasts — measure it.** The filter runs inside
the assembly of each model call and is handed that call's messages, and the same
ordering repeats inside the tool-execution loop. So the visible set is **recomputed every model call rather than accumulated**, and the
only open question — the one the table below turns into a plan — is what that
recomputation reads out of the conversation: whether a search performed on call
one still puts its tools in the specifications on call three, and whether it
still does so on the first call of the next turn.

Answer it by observation rather than by reading release notes. Wire the default
strategy into a scratch configuration, run one turn that searches and then keeps
working, serialize the specifications sent on every model call of that turn, and
look for the tool the search returned. Then send a second turn on the same
conversation and look again. Three outcomes, three different plans:

| What you observe | What it decides |
|---|---|
| The disclosure holds for the rest of the **turn** | a turn may search twice and accumulate; `maxResults` is sized for one step of a task, not for the whole task |
| It holds for **one model call** only | every tool a single step needs must arrive from a **single** search, so `maxResults` is sized by the largest number of tools any one step uses, and repeated searches buy round trips without accumulating |
| It holds across **turns** in the conversation | the visible set grows as a conversation runs, so standing cost is not constant, and a tool disclosed before a caller's permissions changed is still in front of the model afterwards — which is fact 1 again, and only the execution guardrail answers it |

The last row also inherits a failure mode `progressive-tool-disclosure` owns:
where visibility is reconstructed from the conversation, anything that evicts,
compacts or summarises history can withdraw a tool mid-conversation with nothing
raised. That argument is not re-run here; the test it implies is, with a
deliberately small memory window.

Record the observation in the plan with the `maxResults` that follows from it. A
`maxResults` chosen without this measurement is a guess that looks like a
setting.

**When the measurement is not affordable, the step has a default and it is named
as one.** Serializing the specifications sent on every model call needs a seam in
the framework's request assembly that some deployments do not expose and some
readers cannot add inside the time this pass has. The pass continues on a named
default rather than a guess: take the **most conservative of the three rows
above** — assume the disclosure holds for **one model call only**, and size
`maxResults` by the largest number of tools any single step of a real task uses,
counted from the query set's multi-step rows rather than from the framework's
`5`. That floor is correct under all three outcomes: sized for the
non-accumulating case it is not wrong when disclosure turns out to accumulate,
only slightly generous, and generous costs tokens per search while the opposite
error costs the turn.

Everything hanging off the measurement then carries the branch instead of the
number. The gate item says **defaulted, not observed**, and shows the tool count
that sized the floor. The multi-search verification row asserts that the turn
completes, rather than that it completed in a particular number of searches.
And the staleness window in `SCOPED-TOOLS.md` is treated as *as long as the
conversation*. Write the missing measurement into the plan as a named follow-up
with the seam that would make it possible: a defaulted `maxResults` that nobody recorded as
defaulted is an observed one by the second retelling.

**Keyword or semantic.** LangChain4j ships `SimpleToolSearchStrategy` (keyword
substring matching) and `VectorToolSearchStrategy` (semantic, over an embedding
model). These are one axis with two ends, not two axes — "vector" *is* the
semantic option. `STRATEGY-AND-DESCRIPTIONS.md` turns the choice into a rule
whose inputs you can count, and then configures the search tool and drives the
description rewrites off the scoring rule that is actually running.

**What each caller may discover.** Only if brief 2 found a model.
`SCOPED-TOOLS.md` maps the model onto the three layers below, and the one
sentence that decides its shape: filter the candidate list **before** it is
scored, never the results after. Post-filtering leaks names through the refusal
channel — the **enumeration oracle** that file closes — and it also loses recall:
a tool the caller may use gets displaced out of the top five by tools they may
not.

### The three layers are not equal

| Layer | What it changes | Holds against a tool name nobody offered? |
|---|---|---|
| **Discovery filter** — search returns fewer tools | what the model knows exists | **No** |
| **Prompt reinforcement** — the model is told what this caller may use | what the model intends | **No** |
| **Execution guardrail** — a denied call does not run | what happens | **Yes** |

Only the third is enforcement. The first two are accuracy and token spend, and
they are worth having for that; they are not worth reporting as security.

One optional addition, and it is not one of the four: where a cheap classifier
already runs in front of the agent and its verdict reaches the turn's prompt,
that verdict can carry a suggested search query.
`STRATEGY-AND-DESCRIPTIONS.md`, *Seeding the first search from a turn
classifier*, carries the seam and the two failure modes that go into the plan
with it.

**Step 2 ends** with all four decisions recorded, each carrying the count or the
observation that produced it — the fourth as the recorded negative on the branch
where brief 2 found no model — and, where the disclosure lifetime was defaulted
rather than measured, the word *defaulted* beside its number.
