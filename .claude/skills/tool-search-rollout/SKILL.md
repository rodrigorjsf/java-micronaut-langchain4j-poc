---
name: tool-search-rollout
description: Roll tool search out over an existing tool layer — census, strategy, description rewrites, scoped discovery, and the execution guardrail that makes the scope real.
disable-model-invocation: true
---

# Rolling out tool search

**Tool search** hides a tool layer behind one always-visible tool. The model
calls that tool with a query, gets back a handful of matching specifications, and
only those enter the request. The standing prompt carries one schema instead of
fifty.

Whether that is the right mechanism at all belongs to
`progressive-tool-disclosure` — the token math, skills versus search, when
disclosure backfires. This skill assumes that question was asked and search won.
It is the rollout: a pass with a start, an approval gate and an artefact.

The pass runs **delegate → decide → propose → approve → execute → verify**, and
the delegation is load-bearing rather than decorative. A tool census and a
permission survey are hours of reading whose raw material the executing agent
will never look at twice — the exact shape a sub-agent is for.
`subagent-context-isolation` owns that doctrine; `RESEARCH-BRIEFS.md` carries the
three briefs, their return schemas and how to read what comes back — open it at
Step 1, and again when the returns land.

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
three layers below is built, reached at Step 2 on the branch where brief 2 found
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

**Too few tools.** Under roughly fifteen, search buys a round trip and a
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
  below.
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

| Column | What goes in it |
|---|---|
| `name` | the tool name exactly as the model sees it |
| `reach` | `static`, `provider` (non-dynamic), or `dynamic` — decided by reading the provider, not by guessing from where the class lives. A fourth value, `unknown`, is what a child writes for a provider it could not locate; it is a transient that Step 1 resolves, never a value Step 2 counts. **Where the provider's answer is computed rather than constant, the cell carries the expression verbatim and the condition that makes it true**, because the same tool is then `provider` on one deployment and `dynamic` on another and every decision downstream has to hold for both |
| `purpose` | one line, in the form *when a turn needs this*, not *what it calls* |
| `vocabulary` | the words a **user** would say for it — three to six literal phrasings, including the ones that do not contain the tool's own nouns |
| `wording` | `ours`, `upstream`, or `unknown` — whether this repository can edit the name and description that ship to the model |
| `effect` | `read`, or `write` plus whether the write is reversible |
| `principals` | who may run it: a check quoted verbatim with its location, or `unknown` |

**Evidence or `unknown`** is the whole reason this column is safe to have. An
inferred permission fact is a fabricated authorization claim arriving with a
green build and an approved plan behind it, and an `unknown` resolved either way
is guesswork: "all principals" is a vulnerability, "admins only" is an outage,
and neither was measured. The cell stays `unknown` in the artefact — a question
for whoever owns the tool — until they answer it.

`vocabulary` is not decoration either. Under a keyword strategy it is the literal
input to the rewrite, and under a semantic one it is the query set the rewrite is
measured with.

`wording` exists because the rewrite step assumes an editable string and a large
share of real tool layers do not have one — anything arriving over MCP, from a
vendored library or from a shared internal package ships its publisher's wording.
Those rows are searchable, `reach` says so, but they are **findable only by
whatever their publisher happened to write**, which was written to document the
tool rather than to match how your users speak. A census without this column
produces a rewrite plan that quietly cannot be executed for a third of its rows.
`STRATEGY-AND-DESCRIPTIONS.md`, *When the wording is not yours*, carries the
four moves and the order to try them; every `upstream` row leaves this pass with
one of them named. `unknown` here means nobody established ownership, and it goes to the
gate as that question.

**A row that is `unknown` in *both* `wording` and `principals` gets its own
disposition**, because each column's rule leaves it with half an answer. It
enters **neither** the rewrite set nor any per-caller filter — there is no string
this repository may edit and no principal evidence a filter could narrow on — and
it goes to the gate as **one line naming both gaps and the person who resolves
each**. Its `reach` is untouched by either gap, so a both-`unknown` row that is
`static` or `provider` **stays in the standing retrieval check** and fails it if
no query reaches it: unresolved is a question about the row, and never an
exemption for it. `RESEARCH-BRIEFS.md`, *Reading the returns*, carries why.

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

Four decisions, in this order. The first is settled here; the other three each
have a sibling that carries them, and the fourth exists only on the branch where
brief 2 found a permission model — on the other branch the decision is the
recorded negative, which is what gate item 9 asks for.

**Is the rollout still viable?** Count the census rows by `reach`. Resolve every
`unknown` first: an unsurveyed provider counted as nothing is a layer that looks
all-dynamic because nobody looked, and the stop below would then be a verdict the
evidence has not earned. Once none are left, if nothing is
`static` or `provider`, stop — see *When not to run this pass*. If some are, the
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

## Step 3 — The approval gate

Nothing is edited before this. The user is shown, in one place:

1. **The census, whole** — including every `unknown` row, which is where the
   questions for other people are.
2. **The strategy choice**, the countable inputs behind it, and the one input
   whose change would flip it.
3. **The search tool's configuration as literal strings** — tool name, tool
   description, argument name, argument description, `maxResults`, `minScore` —
   with the framework default beside each one you are changing.
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
| Every searchable tool is reachable | each `static` / `provider` tool is returned by at least one query set entry, within `maxResults` and **at or above** `minScore`. This is the one check that outlives the pass — `STRATEGY-AND-DESCRIPTIONS.md` turns it into a standing build check, partitioned by `reach`, that a later unfindable tool fails |
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
