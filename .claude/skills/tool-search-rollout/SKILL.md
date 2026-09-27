---
name: tool-search-rollout
description: Run when tool search was chosen for an existing tool layer and must be rolled out — census, strategy, description rewrites, scoped discovery, and the execution guardrail that makes the scope real.
disable-model-invocation: true
---

# Rolling out tool search

**Tool search** hides a tool layer behind one always-visible tool. The model
calls that tool with a query, gets back a handful of matching specifications, and
only those enter the request. The standing prompt carries one schema instead of
fifty.

Whether that is the right mechanism at all belongs to
`progressive-tool-disclosure` — the token math, which tools stay searchable
beside skills, when disclosure backfires. This skill assumes that question was asked and search won.
It is the rollout: a pass with a start, an approval gate and an artefact.

The pass runs **delegate → decide → propose → approve → execute → verify**, and
the delegation is load-bearing rather than decorative. A tool census and a
permission survey are hours of reading whose raw material the executing agent
will never look at twice — the exact shape a sub-agent is for.
`subagent-context-isolation` owns that doctrine; `RESEARCH-BRIEFS.md` carries the
three briefs, their return schemas and how to read what comes back — open it at
Step 1, and again when the returns land.

| You are here because | Start at |
|---|---|
| you are deciding whether to run this pass at all | *Two facts govern everything below*, then *When not to run this pass* |
| you are sending the research | Step 1, [`RESEARCH-BRIEFS.md`](RESEARCH-BRIEFS.md) and [`CENSUS-SCHEMA.md`](CENSUS-SCHEMA.md) |
| the returns landed and the decisions are next | [`DECIDE.md`](DECIDE.md), with [`STRATEGY-AND-DESCRIPTIONS.md`](STRATEGY-AND-DESCRIPTIONS.md) and, where a permission model exists, [`SCOPED-TOOLS.md`](SCOPED-TOOLS.md) |
| the decisions are made and nothing is edited yet | [`APPROVAL-GATE.md`](APPROVAL-GATE.md) |
| the gate approved | [`EXECUTE-AND-VERIFY.md`](EXECUTE-AND-VERIFY.md) |
| you are closing the pass | *Done when* |

## Two facts govern everything below

### 1. Search filters what the model sees, never what it can run

When the search filter runs it recomputes the **specifications sent to the
model** and leaves the available-tool list and the executor map whole. Execution
stays a plain lookup of whatever tool name the model emitted. In LangChain4j that
is literally `toolExecutors.get(toolRequest.name())`, and the filter never
touches that map.

So a name the model produces from an earlier turn, from a guess at a naming
convention, or from text somebody else wrote into a retrieved document, still
runs. Search never revealed it, and search is not the thing that would have
stopped it.

**Tool search is discovery. It is not access control.** Every permission sentence
in this skill rests on that one, and so does `SCOPED-TOOLS.md` — how each of the
three layers is built, reached at Step 2 ([`DECIDE.md`](DECIDE.md)) on the branch where brief 2 found
a permission model.

### 2. A dynamic tool provider is invisible to search, in both directions

LangChain4j assembles a turn's tools in a fixed order:

```
context = createContextFromStaticToolsAndProviders(...)  // static tools + NON-dynamic providers
if (toolSearchService != null)
    context = toolSearchService.adjust(context, ...)     // the search filter runs HERE
context = refreshDynamicProviders(context, ...)          // dynamic providers, AFTER the filter
```

`ToolProvider.isDynamic()` defaults to `false`. A provider that answers `false`
has its tools folded into the static list **before** the filter, so they are
searchable like any other tool. A provider that answers `true` is refreshed
**after** the filter, on every model call — so its tools are never hidden by
search and never findable through it either. The same ordering repeats inside the
tool-execution loop.

Three consequences run through the rest of the pass.

- The census cannot be a list of names. It records, per tool, **how that tool
  reaches the model**, because only the static and non-dynamic-provider paths are
  searchable at all.
- `SearchBehavior.ALWAYS_VISIBLE` is **inert while the provider its tool arrives
  through is dynamic** — that tool was never at risk of being hidden — and
  **load-bearing exactly when the provider is, or can become, non-dynamic.** A
  provider's answer is frequently computed rather than constant, so the marking
  is kept and read as a claim about every state the provider can reach.
  `STRATEGY-AND-DESCRIPTIONS.md` carries the worked case, and is where each
  marking is decided.
- A repository can hold dynamic providers without anyone having decided to. In
  LangChain4j's skills module a provider reports itself dynamic as soon as any
  skill carries tools, and a skill's own `@Tool` methods are wrapped into a
  skill-scoped provider — so *one* skill with *one* tool turns the whole provider
  dynamic. This is why the census asks the question of every tool instead of
  assuming an answer for the layer.

## What the pass produces

| Artefact | Where it comes from |
|---|---|
| A **tool census**, one row per tool, `unknown` rows intact | sub-agent, brief 1 |
| A **permission finding** — the model this project has, or the evidence that it has none | sub-agent, brief 2 |
| A **query set** of real user phrasings with their expected tools, written before any edit | sub-agent, brief 3, or the parent |
| A **strategy decision** with the countable inputs that produced it | you, from the census |
| A **diff proposal** — every literal string that will change | you |
| The **executed change**, plus the verification that closes the pass | you, after approval |
| A **standing retrieval check** in the build, and a **record of what search does in production** (`STRATEGY-AND-DESCRIPTIONS.md`) | you, inside the executed change |

Commit the census and the permission finding to the repository even if the
rollout is abandoned: the next pass starts from them. Every artefact above
describes the layer on the day it was measured; the last row is what notices when
that stops being true.

## When not to run this pass

Four checks, and they do not all answer at the same moment. The first two answer
before any research and belong at the top of the run. The third is answered *by*
brief 2 — whether a permission model exists is the survey's finding, never an
assumption made ahead of it. The fourth is answerable only from the census.
Reaching either of the last two is a completed pass, not a failed one.

**Too few tools.** Under ten, search buys a round trip and a
retrieval failure mode in exchange for a few hundred tokens, and a wrong pick is
still one description's fault. `agentic-tool-boundary` fixes that layer;
`progressive-tool-disclosure` carries the threshold argument. Stop here.

**Every turn needs the same three tools.** Then disclosure is not progressive,
only delayed, and every turn pays the search round trip to rediscover what it
always needs. Mark those tools always-visible and stop; there may be nothing left
to hide.

**No permission model exists and nobody has asked for one.** Scoping is one part
of this pass, not its justification. A project with a single class of caller gets
the census, the strategy, and the absence written down as a finding;
`SCOPED-TOOLS.md`, *Finding it, and the branch where there is nothing*, carries
that branch's three deliverables.

**Every tool reaches the model through a dynamic provider.** Then search filters
an empty set, and enabling it changes nothing except the standing cost of one
extra tool. You find this out at the decide step, after the census, and the
correct outcome is to stop and hand back the census plus a recommendation: which
providers would have to become non-dynamic, what that costs, and what is gained.
A run that ends there produced two artefacts and prevented a change that would
have shipped as a silent no-op. Report it as a result.

## Step 1 — Delegate the research

Two briefs are required, one is conditional. Send them in parallel, with the one
seam between them handled deliberately rather than ignored — it is spelled out
below the bullets. Copy them verbatim from `RESEARCH-BRIEFS.md`, which also
carries each one's return schema and the rule that governs all three: **a
sub-agent drafts and measures; it never signs off.** A returned census is
evidence. The strategy decision, the rewrites and the plan are yours.

- **Brief 1 — the tool census.** Every tool, its reach path, and the columns
  in [`CENSUS-SCHEMA.md`](CENSUS-SCHEMA.md).
- **Brief 2 — the permission survey.** What authorization model this project
  already has, reported as searches run and hit counts, with the negative case
  written as evidence rather than as an impression.
- **Brief 3 — the vocabulary harvest**, when the repository holds real user
  turns: transcripts, evaluation fixtures, support tickets, issue titles. Its
  return is the phrasings *verbatim*, never a summary of them, because that
  material is the query set.

**Briefs 1 and 2 have one dependency, and a parallel send has to survive it.**
Brief 1's `principals` column is filled from checks whose verbs, and this
codebase's principal type, are exactly what brief 2's tiers 2 and 4 go and
discover. Sent in parallel, brief 1 returns that column **provisional** — a
column of `unknown` backed by the fallback verb list brief 1 carries is the
column behaving as designed. **The parent completes it after both returns land**,
by matching brief 2's quoted decision points and principal type against brief 1's
per-tool execution paths, under the same rule the child had: **evidence or
`unknown`.** `RESEARCH-BRIEFS.md`, *Reading the returns*, carries why a cell
filled from anything else is worse in the parent's hands than in the child's.

### The census schema

One row per tool; every column is filled from evidence or reads `unknown`, and an
`unknown` row stays in the census → [`CENSUS-SCHEMA.md`](CENSUS-SCHEMA.md).

**Step 1 ends** when all three returns are in hand, not when the census is.
Brief 1: every registration route it listed has its tools in the census, every
`principals` cell holds a quoted check or `unknown`, and every `unknown` in any
cell appears in the *could not determine* list beside the artefact or the person
who would fill it. Brief 2: a model named with its quoted evidence, or the three
negatives written down as the finding — the survey is the input to Step 2's
fourth decision and to every branch of `SCOPED-TOOLS.md`, so an open survey is an
open step. Brief 3: the rows, the counts and the ZERO-phrasings list, or the
recorded reason it was not run.

## Step 2 — Decide

Four decisions, in order: **is the rollout still viable** (count the census by
`reach`, resolving every `unknown` first); **how long one search's disclosure
lasts** — measured, not assumed; **which strategy**; and, only where brief 2 found
a permission model, **how the scope maps onto the three layers**, which are not
equal → [`DECIDE.md`](DECIDE.md).

## Step 3 — The approval gate

**Nothing is edited before this.** The user sees, in one place, the census whole,
the strategy and the input that would flip it, the search tool's configuration as
literal strings, every name and description that changes, the vocabulary move for
every row whose wording is not `ours`, and the plan → [`APPROVAL-GATE.md`](APPROVAL-GATE.md).

## Step 4 — Execute

Renames first and separately, then descriptions, then the vocabulary moves the gate
approved, then always-visible tools, then the strategy on the builder, then the
execution guardrail — in that order, because each step's failures read most easily
alone → [`EXECUTE-AND-VERIFY.md`](EXECUTE-AND-VERIFY.md).

## Step 5 — Verify

Standing cost fell, every searchable tool is reachable, every reach path still
holds, multi-tool and two-search turns complete, the guardrail refuses a tool
search never returned, and the filter is per caller → the checks and what a pass
looks like are in [`EXECUTE-AND-VERIFY.md`](EXECUTE-AND-VERIFY.md).

## Done when

- every tool in the layer has a census row, and every `principals` cell holds a
  quoted check or `unknown` — no inferred permissions;
- every row's `reach` was read out of the provider — with the expression and its
  condition recorded wherever that answer is computed rather than constant — the
  plan says what happens to the `dynamic` ones, and the verification re-derived
  every searchable row's reach path from the real provider graph rather than
  from the census or a test double;
- every tool marked always-visible was checked against the `principals` column
  and any tool the scope was meant to hide that must stay marked for
  availability reasons is named here with the layer-3 row that speaks for it,
  and each marking is
  recorded with the `reach` of the provider it sits behind rather than stripped
  because that provider is dynamic today;
- every row whose `wording` is not `ours` carries a named move, or is recorded at
  the gate as a tool this pass leaves unfindable and why;
- every row that is `unknown` in both `wording` and `principals` went to the gate
  as one line naming both gaps and their owners, entered neither the rewrite set
  nor any per-caller filter, and stayed in the standing retrieval check on the
  strength of its `reach`;
- the strategy choice names the countable inputs that produced it and the one
  that would flip it;
- the framework version is pinned exactly, and the plan names what an upgrade of
  it has to re-read before landing;
- the disclosure lifetime was **observed** from serialized specifications and
  `maxResults` follows from that observation rather than from the framework
  default — or the measurement was declared unaffordable at the gate, in those
  words, with `maxResults` sized by the largest single-step tool count and the
  missing seam named as a follow-up;
- the search tool's six settings ship as literal strings that appeared at the
  gate, not as framework defaults nobody looked at;
- every changed name and description appeared at the gate in full, before and
  after;
- the query set was written before the edits it measures, and it carries
  negative rows as well as positive ones — turns labelled to a neighbouring tool
  and turns labelled *no tool should serve this* — with a before-the-rewrite
  reading of both to compare against;
- a turn requiring two separate searches was driven end to end and completed,
  and the search tool's description says the tool may be called again;
- the standing retrieval check runs — the offline form gating the build, the
  model-dependent form on a tag or a schedule and reporting rather than
  blocking — partitioned by `reach`, and a
  searchable tool that no query reaches fails it — with no searchable tool left
  out of it for want of a query-set row;
- the search record ships in the same change as the strategy, and its provenance
  field has three values, not two;
- every cache added on the search path has a key that contains everything the
  per-caller filter reads;
- the layer is called *scoped* only if a call the model was never offered was
  actually driven at the execution path and refused;
- the verification table has a result in every row, and the rows that did not
  run say so.
