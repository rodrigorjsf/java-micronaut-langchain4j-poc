# Steps 4 and 5 — execute and verify

Reference for [`tool-search-rollout`](SKILL.md), Steps 4 and 5. Open it once the
gate has approved: the order the change lands in, and the checks that close the
pass.

## Step 4 — Execute

In this order, because each step's failures are easiest to read in isolation:

1. Rename tools, if any rename survived the gate. Names move routing on their own
   and they carry double retrieval weight under a keyword strategy — do them
   first and separately.
2. Rewrite descriptions.
3. Carry the vocabulary for every row whose `wording` is not `ours`, by whichever
   of the moves in `STRATEGY-AND-DESCRIPTIONS.md` the gate approved. These land
   before the strategy is registered because most of them live *inside* the
   strategy being registered.
4. Mark the always-visible tools.
5. Register the strategy on the AI-service builder
   (`AiServices.builder(X.class)....toolSearchStrategy(strategy)`), with the
   configuration from gate item 3 — and with the search record from gate item 14
   (`STRATEGY-AND-DESCRIPTIONS.md`) in the same change.
6. Add the execution guardrail, if the pass has one.
7. Add the per-caller filter inside the strategy, if the pass has one.

Steps 6 and 7 in that order, not the reverse. The guardrail is the control; the
filter is the optimisation on top of it, and a filter shipped without a guardrail
is the plan claiming enforcement it does not have.

The record is inside step 5 rather than after it because instrumentation added a
month later starts its history a month late, and the month it missed is the one
where the vocabulary was wrong and the `maxResults` was guessed.

**Step 4 ends** when every literal string approved at the gate is in the tree,
and nothing the gate sent back is.

## Step 5 — Verify

| Check | What a pass looks like |
|---|---|
| Standing cost fell | serialize the specifications the framework actually sends, before and after; the hidden tools are gone and the search tool is there |
| Every searchable tool is reachable | each `static` / `provider` tool is returned by at least one query set entry, within `maxResults` and **at or above** `minScore` (the score floor — under semantic search a constant in your own strategy, since the library builder has no setter). This is the one check that outlives the pass — `STRATEGY-AND-DESCRIPTIONS.md` turns it into a standing build check, partitioned by `reach`, that a later unfindable tool fails |
| Every census row's reach path still holds | re-derive each searchable row's `reach` from the **real provider graph the application assembles** — not from the census file, which records the day it was written, and not from a test double — and fail on any row now arriving through a provider that answers dynamic. This is the regression the ordering fact makes consequential and it is the one that ships in **silence**: a provider whose dynamic answer is computed flips when a condition somewhere else changes, and in that instant its tools stop being hideable and stop being findable, with no exception, no failing assertion and no diff anybody reads as related. `STRATEGY-AND-DESCRIPTIONS.md` says why a test double cannot catch it, and turns this into the standing check |
| Always-visible tools need no search | a turn using one of them shows no search call |
| Multi-tool turns behave as measured | a request needing two tools completes, and the way it completes matches the disclosure lifetime observed in Step 2 — one search or two. A model that searches once and gives up against an accumulating disclosure is a finding about `maxResults` or about the search tool's description; against a non-accumulating one it is the framework working as observed, and `maxResults` was sized wrong. Where Step 2 defaulted rather than measured, this row asserts only that the turn completes |
| A turn needing **two searches** completes | drive a turn whose tools cannot all come back from one search — more targets than `maxResults`, or targets no single query reaches — and assert it finishes with the right tools rather than with a confident partial answer. This is the failure that ships silently: with every hidden tool behind a search call, a model that searches once, takes the partial set and proceeds produces a fluent answer, no error and no red build. Before treating it as a `maxResults` problem, read the search tool's own description: **if it does not say the tool may be called again, the model was never told it may**, and the fix is one clause there |
| The guardrail holds | drive a tool name that search never returned, directly into the execution path, and assert refusal — this is the only check that tests fact 1 |
| The filter is per-caller | the same query under two principals returns different candidate sets, and the smaller one is a *strict* subset of the larger |
| No cache crosses callers | if the pass added a cache anywhere on the search path, the per-caller check above still passes with the cache warm, and passes in both principal orders |
| A refusal reads correctly | the message is terminal, actionable, about the account rather than the agent, and names no tool the caller was never offered |
| The record is there and is readable | one search and one tool call produce every field in `STRATEGY-AND-DESCRIPTIONS.md`, *What the record shows once it ships*, and the provenance field is populated with one of its three values rather than left empty |

**What green does not prove.** That the model will actually search rather than
answering from memory — that is a behavioural property of the prompt, and only
turn-level evaluation shows it. That recall holds for phrasings absent from your
query set; the set is a sample and its size is `agentic-evals`' question. That
latency is acceptable under a semantic strategy, which adds an embedding call per
search on the request path. And that the layer is *scoped*: only the guardrail
row above speaks to that, and it speaks about exactly the one tool name you
drove.

The standing check closes one of the gaps this list used to carry — a tool added
next month can no longer arrive unfindable, because the build fails on it. It
cannot close the one underneath: **every row of the query set is a phrasing
somebody on this team wrote down**, and a team writes down the words it uses. A
green standing check six months from now means the vocabulary of the day the pass
ran still retrieves; it says nothing about the words users have started using
since. Only production says that, and only if the pass left something behind that
records it — `STRATEGY-AND-DESCRIPTIONS.md`, *What the record shows once it
ships*, is the handful of fields that does, approved as gate item 14 and shipped
in step 4.
