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
briefs this pass sends.

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
in this skill and in `SCOPED-TOOLS.md` rests on that one.

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
  through is dynamic**: that tool was never at risk of being hidden, so there is
  nothing for the marking to exempt it from. The rule that follows is *not*
  "strip the marking" — it is that **the marking is load-bearing exactly when
  the provider is, or can become, non-dynamic.** A provider's answer is
  frequently computed rather than constant: LangChain4j's skills module answers
  with `!skillScopedProviders.isEmpty()`, so one and the same provider is
  dynamic on a deployment where some skill carries tools and non-dynamic on one
  where none does. On the second deployment the marking is the only thing
  keeping those tools in front of the model — which is why the library ships it
  on its own two management tools rather than omitting it as redundant. Read the
  marking as a claim about every state the provider can be in, never as a claim
  about the state it happens to be in today.
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
| A **standing retrieval check** in the build, and a **record of what search does in production** | you, inside the executed change |

The census and the permission finding are worth committing to the repository even
if the rollout is abandoned. They outlive this pass and the next one starts from
them. The last row outlives it in a stronger sense: every other artefact here
describes the layer on the day it was measured, and those two are what notice
when that stops being true.

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
of this pass, not its justification. A project with a single class of caller
should get the census and the strategy and none of `SCOPED-TOOLS.md`'s
machinery — and specifically must not have a permission model invented so that a
scoping step has something to scope to.

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
Brief 1's `principals` column is filled from checks on each tool's execution
path — and the verbs those checks are spelled with, together with the name of
this codebase's principal type, are exactly what brief 2's tiers 2 and 4 go and
discover. Sent in parallel, brief 1 is grepping for names nobody has given it
yet, and its honest return is a column of `unknown`. That is not a failed run;
it is the column behaving as designed. So the census arrives with `principals`
**provisional**, and **the parent completes that column after both returns
land**, by matching brief 2's quoted decision points and principal type against
brief 1's per-tool execution paths.

The parent is bound by the same rule the child was: **evidence or `unknown`.** A
verb list from brief 2 tells you what to go and look for; it never tells you
what any particular tool checks. A cell filled in because the survey found a
permission model *somewhere in the repository* is an inferred permission with
the parent's authority behind it, which is worse than the child's version rather
than better. The completion pass reads code and quotes it, or it leaves the cell
alone.

Brief 1 carries its own fallback verb list for the branch where brief 2 finds no
permission model at all — a live outcome of this pass, not a degenerate one. The
list is brief 2's own tier-2 verbs, copied into brief 1 verbatim so the two
briefs cannot drift apart, and it exists so that the census's `principals` sweep
has something concrete to grep for on a run where the survey has nothing to hand
it. On that branch a column of `unknown` backed by a named list of patterns and
their hit counts is a finding; a column of `unknown` backed by nothing is a gap.

### The census schema

| Column | What goes in it |
|---|---|
| `name` | the tool name exactly as the model sees it |
| `reach` | `static`, `provider` (non-dynamic), or `dynamic` — decided by reading the provider, not by guessing from where the class lives. **Where the provider's answer is computed rather than constant, the cell carries the expression verbatim and the condition that makes it true**, because the same tool is then `provider` on one deployment and `dynamic` on another and every decision downstream has to hold for both |
| `purpose` | one line, in the form *when a turn needs this*, not *what it calls* |
| `vocabulary` | the words a **user** would say for it — three to six literal phrasings, including the ones that do not contain the tool's own nouns |
| `wording` | `ours`, `upstream`, or `unknown` — whether this repository can edit the name and description that ship to the model |
| `effect` | `read`, or `write` plus whether the write is reversible |
| `principals` | who may run it: a check quoted verbatim with its location, or `unknown` |

The `principals` column has one rule, and it is the whole reason the column is
safe to have: **evidence or `unknown`.** A census that infers a permission fact
from surrounding code is a fabricated authorization claim arriving with a green
build and an approved plan behind it. `unknown` never becomes "all principals"
and never becomes "admins only" — the first is a vulnerability, the second is an
outage, and neither was measured. An `unknown` row is a question for whoever owns
the tool, and it stays `unknown` in the artefact until they answer.

`vocabulary` is not decoration either. Under a keyword strategy it is the literal
input to the rewrite, and under a semantic one it is the query set the rewrite is
measured with.

`wording` exists because the rewrite step assumes an editable string and a large
share of real tool layers do not have one. Tools arriving over MCP or any other
external protocol come with a name and a description their publisher wrote, and
so do tools from a vendored library or a shared internal package. Those rows are
searchable — `reach` says so, and nothing about being externally supplied hides
them — but they are **findable only by whatever their publisher happened to
write**, which was written to document the tool, not to match how your users
speak. A census that does not carry this column produces a rewrite plan that
quietly cannot be executed for a third of its rows.
`STRATEGY-AND-DESCRIPTIONS.md` carries what to do for an `upstream` row, and the
move is never "leave it and hope". `unknown` here means nobody established
ownership; it does not collapse to `ours`.

**A row that is `unknown` in *both* `wording` and `principals` gets its own
disposition**, because it is the row most likely to stall the pass and the two
columns' separate rules leave it with two half-answers otherwise. Nobody
established who owns its text, and nothing was measured about who may run it. So
it enters **neither** the rewrite set nor any per-caller filter: there is no
string this repository may edit, and no principal evidence a filter could narrow
on, and supplying either from inference is the exact fabrication each column
exists to prevent. It goes to the gate as **one line naming both gaps and the
person who resolves each**, not as two entries in two lists that get answered by
two different people at two different times.

What such a row does not lose is its `reach`. That column was read out of the
provider and is untouched by the other two, so a both-`unknown` row that is
`static` or `provider` **stays in the standing retrieval check** and fails it if
no query reaches it. Being unresolved is a question about the row, never an
exemption for it — a row dropped from the check because two of its cells are
open is the unfindable tool this pass exists to prevent, arriving with
paperwork.

## Step 2 — Decide

Four decisions, in this order, each with a sibling that carries it.

**Is the rollout still viable?** Count the census rows by `reach`. If nothing is
`static` or `provider`, stop — see *When not to run this pass*. If some are, the
rollout covers those and the dynamic ones stay permanently visible; say so
explicitly in the plan rather than letting the reader assume search covers
everything.

**How long one search's disclosure lasts — measure it, do not assume it.** The
filter runs inside the assembly of each model call and is handed that call's
messages, and the same ordering repeats inside the tool-execution loop. So the
visible set is **recomputed every model call rather than accumulated**, and the
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
readers cannot add inside the time this pass has. That must not stall the pass,
and it must not license a guess either — so take the **most conservative of the
three rows above**: assume the disclosure holds for **one model call only**, and
size `maxResults` by the largest number of tools any single step of a real task
uses, counted from the query set's multi-step rows rather than from the
framework's `5`. That floor is correct under all three outcomes: sized for the
non-accumulating case it is not wrong when disclosure turns out to accumulate,
only slightly generous, and generous costs tokens per search while the opposite
error costs the turn.

Everything hanging off the measurement then carries the branch instead of the
number. The gate item says **defaulted, not observed**, and shows the tool count
that sized the floor. The multi-search verification row asserts that the turn
completes, rather than that it completed in a particular number of searches.
And the staleness window in `SCOPED-TOOLS.md` is treated as *as long as the
conversation*, which is the assumption that costs nothing when it is wrong. Write
the missing measurement into the plan as a named follow-up with the seam that
would make it possible: a defaulted `maxResults` that nobody recorded as
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
channel, and it also loses recall — a tool the caller may use gets displaced out
of the top five by tools they may not.

### The three layers are not equal

| Layer | What it changes | Holds against a tool name nobody offered? |
|---|---|---|
| **Discovery filter** — search returns fewer tools | what the model knows exists | **No** |
| **Prompt reinforcement** — the model is told what this caller may use | what the model intends | **No** |
| **Execution guardrail** — a denied call does not run | what happens | **Yes** |

Only the third is enforcement. The first two are accuracy and token spend, and
they are worth having for that; they are not worth reporting as security.

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
   each one, in the order `STRATEGY-AND-DESCRIPTIONS.md` sets — vocabulary
   carried in the strategy instead of in the description, a specification
   published from a provider you own, a request to whoever publishes the tool,
   and only then an exemption from search, which is not a retrieval fix and puts
   that tool in the standing prompt permanently. A row left with no move is a
   tool this pass is choosing to make unfindable, and it goes to the gate in
   those words.
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
8. **Every tool proposed for `ALWAYS_VISIBLE`**, with the reason it must not be
   hideable, and two things that reason has to survive. First: **none of them is
   a tool the scope was meant to hide.** The marking's guarantee is that search
   never hides the tool; the per-caller filter of layer 1 works by narrowing what
   search returns — so a marked tool sits outside anything that filter can
   narrow, and marking a sensitive tool always-visible quietly removes it from
   the scope while the rest of the plan still reads as though the scope covers
   it. Check this item against the census's `principals` column, row by row, and
   say at the gate that you did. Second: for each marked tool, the `reach` cell
   of the provider it arrives through, **including the expression where that
   answer is computed** — the marking is inert while the provider is dynamic and
   load-bearing the moment it is not, so what is being approved is a claim about
   every state that provider can reach, not the one it is in this week.
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
    sentences. The alternative that must not happen quietly is a check scoped to
    "the tools that already have rows", which restores exactly the hole it was
    built to close.
14. **The instrumentation** shipping with the change: the fields recorded per
    search and per tool call, where they are written, how long they are kept, and
    what is redacted before they get there.
15. **The rollback** — what reverting looks like, and what the layer behaves like
    with search switched back off.
16. **What this pass will not do**, named: the `unknown` rows it cannot resolve,
    the dynamic providers it leaves visible, the object-level permission
    questions it cannot answer at discovery time.

**Four of those sixteen are conditional — three can disappear outright and one
appears in one of two forms — and a run that skips one is still a complete
gate.** Items 9, 10 and 11 turn on whether brief 2 found a permission
model — 10 and 11 disappear without one, and 9 changes into its negative branch
rather than vanishing. Item 5 turns on whether some census row's `wording` is
not `ours`. A gate
showing fewer than sixteen because a condition did not fire is finished, and it
says which items and which condition. A gate missing an *unconditional* item is
unfinished, and the difference has to be legible at a glance — otherwise a
reader sees a short list and cannot tell a legitimate skip from an omission. The
alternative that looks tidier and is worse is presenting all sixteen every time
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
   in the same change.
6. Add the execution guardrail, if the pass has one.
7. Add the per-caller filter inside the strategy, if the pass has one.

Steps 6 and 7 in that order, not the reverse. The guardrail is the control; the
filter is the optimisation on top of it, and a filter shipped without a guardrail
is the plan claiming enforcement it does not have.

The record is inside step 5 rather than after it for a reason that does not
improve with age: instrumentation added a month later starts its history a month
late, and the month it missed is the one where the vocabulary was wrong, the
`maxResults` was guessed and every question anyone asks afterwards is about
exactly that period.

## Step 5 — Verify

| Check | What a pass looks like |
|---|---|
| Standing cost fell | serialize the specifications the framework actually sends, before and after; the hidden tools are gone and the search tool is there |
| Every searchable tool is reachable | each `static` / `provider` tool is returned by at least one query set entry, within `maxResults` and **at or above** `minScore`. This is the one check that must not stop when the pass does — `STRATEGY-AND-DESCRIPTIONS.md` turns it into a standing build check, partitioned by `reach`, that a later unfindable tool fails |
| Every census row's reach path still holds | re-derive each searchable row's `reach` from the **real provider graph the application assembles**, and fail on any row now arriving through a provider that answers dynamic. Not from the census file, which records the day it was written, and **not from a test double**, which answers whatever it was constructed to answer and therefore cannot catch this at all. This is the regression the ordering fact makes consequential and it is the one that ships in silence: a provider whose dynamic answer is computed flips when a condition somewhere else changes, and in that instant its tools stop being hideable and stop being findable, with no exception, no failing assertion and no diff anybody reads as related |
| Always-visible tools need no search | a turn using one of them shows no search call |
| Multi-tool turns behave as measured | a request needing two tools completes, and the way it completes matches the disclosure lifetime observed in Step 2 — one search or two. A model that searches once and gives up against an accumulating disclosure is a finding about `maxResults` or about the search tool's description; against a non-accumulating one it is the framework working as observed, and `maxResults` was sized wrong. Where Step 2 defaulted rather than measured, this row asserts only that the turn completes |
| A turn needing **two searches** completes | drive a turn whose tools cannot all come back from one search — more targets than `maxResults`, or targets no single query reaches — and assert it finishes with the right tools rather than with a confident partial answer. This is the failure that ships silently: with every hidden tool behind a search call, a model that searches once, takes the partial set and proceeds produces a fluent answer, no error and no red build. Before treating it as a `maxResults` problem, read the search tool's own description: **if it does not say the tool may be called again, the model was never told it may**, and the fix is one clause there |
| The guardrail holds | drive a tool name that search never returned, directly into the execution path, and assert refusal — this is the only check that tests fact 1 |
| The filter is per-caller | the same query under two principals returns different candidate sets, and the smaller one is a *strict* subset of the larger |
| No cache crosses callers | if the pass added a cache anywhere on the search path, the per-caller check above still passes with the cache warm, and passes in both principal orders |
| A refusal reads correctly | the message is terminal, actionable, about the account rather than the agent, and names no tool the caller was never offered |
| The record is there and is readable | one search and one tool call produce the fields below, and the provenance field is populated with one of its three values rather than left empty |

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
records it.

## What search leaves behind, once it ships

Every number this pass produced came from a query set written by the people who
wrote the tools. That is the best available input before launch and it is a
sample of one team's vocabulary. The mechanism that corrects it afterwards is a
record of what search was actually asked for and what it actually returned, and
it costs a handful of fields.

**Per search:**

| Field | Why it is the one you will want |
|---|---|
| The query **as the model sent it**, verbatim | the input to every other conclusion. Under a keyword strategy record the cleaned terms beside it, because cleaning is where a sentence becomes a dozen indiscriminate terms |
| The names returned, **with their scores and their ranks** | a target arriving consistently at rank four under a `maxResults` of five is a week from disappearing, and nothing else shows that coming. Record the rank **as observed** and read it as a symptom rather than a guarantee: what the framework specifies is a score and a cut, not an ordering or a tie-break, so a target sitting at the cut line is a warning without being a prediction. The **score** beside it is the sturdier of the two numbers |
| The size of the candidate set the scorer actually saw | separates "scored badly" from "was filtered out before scoring" — under per-caller narrowing those look identical downstream |
| Whether the search returned **nothing** | the highest-value row in the whole record: it is a user's phrasing, in production, that reaches no tool. These are query-set rows waiting to be added |

**Per tool call, one field: where this tool came from.** Make it three-valued,
because two values throw away the interesting case.

| Value | Meaning |
|---|---|
| `from-search` | this name appeared in a search result earlier in this conversation |
| `always-visible` | it never needed a search; it is in the standing set by design |
| `neither` | **the alarm.** The model called a tool that no search offered it and that is not always-visible |

The first two values buy the split every offline measurement in this pass is
built on: *search never returned it* versus *search returned it and the model
chose something else*. Those two produce the same symptom — the wrong tool ran —
and they have nothing in common as problems. The first is a retrieval failure and
belongs to the rewrite; the second is a routing failure and belongs to the
descriptions' other job.

The third value is fact 1 in production. The guardrail test proves refusal for
one name you drove by hand; this field counts how often it happens for real, on
names nobody predicted. A non-zero count is not automatically an attack — a
resumed conversation and a model reusing a name from earlier both land here — but
it is the only place the system ever says out loud that the executable set is
wider than the visible one.

**What the record is read for**, on a fixed cadence rather than when something
breaks: empty searches become new query-set rows and new `vocabulary` cells;
rank-versus-`maxResults` *argues for* a `maxResults`, under the hedge above —
observed rank is not a documented guarantee, so a tool that must survive the cut
gets fixed at the description rather than by tuning the window around where it
happened to land; the scores of results nobody used set `minScore`; a searchable tool that has never been returned is either unfindable
or dead, and the census says which; and an always-visible tool whose provenance
value never appears was exempted for a turn nobody makes, so its permanent place
in the standing prompt has no buyer. Each of those edits then re-enters this pass
at the rewrite step, where it is measured against the query set like any other.

**Two constraints on how it is written.** Search terms are user text — they carry
whatever a user typed, so they are subject to the same retention and redaction
rules as any other message content, not looser ones because they look like
telemetry. And tool names are a bounded set while query terms are unbounded: name
a metric dimension after the tool, never after the query, and keep the queries
themselves in whatever store already holds turn-level detail.
`llm-cost-observability` owns the plumbing that makes a turn attributable; this
is the one search-shaped record to put on top of it.

## Optional — seed the first search from the turn's classifier

Some systems already run a cheap classifier in front of the agent and route its
verdict into the turn's prompt as a hint — a category, a route label, a skill
hint in a turn-context block. `llm-triage-gate` owns that classifier. Where one
exists, the same verdict can carry a **suggested search query**, so the model's
first search is seeded rather than guessed. It is the cheapest recall improvement
available, because it fixes the query at the one point in the system that has
already read the user's message and decided what it is about.

The seam is generic: the classifier's structured verdict grows one optional
field; the prompt assembly renders it inside the same block the other hints go
in; the model is free to ignore it. Two failure modes, both worth writing into
the plan.

**A suggestion the model reads as an instruction.** Rendered as "use the invoice
tool", the hint stops being a seed and becomes a routing decision made by a model
too cheap to make it, and the search that would have corrected a bad hint never
runs. Render it as *terms to start from*, and never let a hint be the only path
by which a tool is reachable — every tool must still be findable from a query
typed by a user who has never heard of the classifier.

**A hint that names a tool this caller may not use.** The classifier does not
know the caller. A hint that names tools re-opens exactly the enumeration channel
the scoping closed: the model narrates the name, the user learns a tool exists
that they were deliberately never shown, and a refusal follows for something they
were never offered. Two fixes, in preference order — have the hint carry search
*terms* rather than tool names, or pass it through the same per-caller filter
before it reaches the prompt.

## Done when

- every tool in the layer has a census row, and every `principals` cell holds a
  quoted check or `unknown` — no inferred permissions;
- every row's `reach` was read out of the provider — with the expression and its
  condition recorded wherever that answer is computed rather than constant — the
  plan says what happens to the `dynamic` ones, and the verification re-derived
  every searchable row's reach path from the real provider graph rather than
  from the census or a test double;
- every tool marked always-visible was checked against the `principals` column
  and none of them is a tool the scope was meant to hide, and each marking is
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
- the standing retrieval check runs in the build, partitioned by `reach`, and a
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
