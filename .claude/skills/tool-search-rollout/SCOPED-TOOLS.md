# Scoping a tool layer to its caller

Reference for [`tool-search-rollout`](SKILL.md), Step 2 and Step 4, on the branch
where brief 2 found a permission model — and on the branch where it found none,
for what that run still ships. How to find the model a project already has, scope
discovery to it, and put enforcement where enforcement can actually sit.

`SKILL.md` states the fact this file implements: search filters what the model
**sees**, never what it can **run**, and of the three layers only the execution
guardrail is enforcement. This file is how each layer is built and what each one
is worth. It does not re-argue the ranking.

## Two questions, and a third thing that is not a permission

Every tool call carries two authorization questions. They have different answers,
different timing and different enforcement points, and most projects collapse
them into one word.

**The capability question** — may this caller use this *kind* of action at all?
*May this account issue a refund?* Answerable from the caller alone, before any
argument exists, and constant for every call of that tool in the turn.

**The object question** — may this caller touch the *thing* the arguments name?
*May this account refund order 4471?* Not answerable until the arguments exist,
different from call to call of the same tool, and a permitted tool with a
forbidden argument is the ordinary case rather than the edge case.

Only the capability question can decide what a caller is **shown**. Only the
object question decides whether a particular call may **run**. A model that
answers only the object question can never justify hiding a tool; a model that
answers only the capability question can never justify letting a call through
unexamined.

The third thing is **binding**, and it is routinely mistaken for a permission. A
tenant id, an org id, an owner id — these are values *supplied from the session*,
not *checked against it*. Their failure mode is not a denial nobody issued; it is
a call that succeeded against the wrong data because the identifier arrived as an
argument the model filled in. The design rule is concrete: **a tool does not take
a tenant, org, account or user identifier as a parameter.** It reads one from the
caller's identity, and where a request genuinely names a different one, that value
is validated against the session's own before anything uses it.

## What a project may already have

The last column is the bridge to everything downstream.

| Model | What it looks like in a codebase | Decidable from |
|---|---|---|
| **Roles** | a `role`/`roles` field or a membership table; constants like `admin`, `owner`, `viewer`; a role→permission map held as config | principal alone |
| **Permission strings** | a flat grant set on the principal — `billing:write`, `orders:*` — and a membership check | principal alone |
| **Attributes and policy** | a policy call taking subject, action, resource and environment; rules kept outside the code as data | **split** — subject and environment are known at turn start, resource is not |
| **Relationships** | tuples of (subject, relation, object); a check shaped `can(user, action, object)`; ownership joins; group or folder inheritance | needs arguments |
| **Per-object grants** | an ACL, `grants` or `shares` table keyed by object; `grant`/`revoke` exposed to ordinary users | needs arguments |
| **Tenant / org scoping** | a tenant column on nearly every table; a filter applied by middleware, an ORM hook, or row-level security | neither — a value to **bind** |
| **Plan entitlements** | a plan or tier field; an entitlement set; a plan→feature map; a paywall check | principal alone |
| **Token scopes** | a `scope` claim; scope requirements declared per route or per downstream API | principal alone — **only if the call carries the caller's token** |

Five notes the table cannot hold, each of which changes what this pass may do.

**Two models are normally present at once** — a capability model (roles, scopes,
plan) *and* an object model (ownership, tenancy, grants). A survey that finds one
and reports it as "the" permission model has stopped halfway, and the half it
missed is usually the one that governs the arguments.

**Relationship and per-object models never justify hiding a tool.** Rolled up to
the tool, the object question degenerates into *may this caller hold this relation
to anything at all*, whose answer is almost always yes — users own their own
documents. Hiding a document tool from someone entitled to use it on one object in
ten thousand is a wrong answer that costs a capability. These models justify
per-argument denial at execution, and nothing else.

**A relationship check is usually a network call**, sometimes several, once per
tool call. That is a real input to any latency budget, and it is why such checks
get batched, cached briefly, or pushed into the data query as a predicate. A cache
here is a stale verdict on a shorter clock — decide its life deliberately rather
than inheriting a client library's default.

**Scopes describe whoever's token is presented, not whoever asked.** An agent
usually calls downstream with a *service* credential. In that arrangement the
scope list is the application's authority, a superset of every individual
caller's, and a downstream that enforces scopes perfectly is enforcing nothing
about the caller. This is the confused-deputy shape, and it is the default state
of most tool layers rather than a mistake somebody made.

**The model nobody documents** is scattered conditionals on a user field, with no
table, no policy artifact and no shared helper. It is a real model — distributed,
un-enumerable, inconsistent, and skipped by every new code path. Report it as what
it is, not as an absence; the distinction changes the recommendation completely.

## Finding it, and the branch where there is nothing

`RESEARCH-BRIEFS.md`, brief 2, is the survey. What matters here is what you do
with each of its two possible answers.

**A model was found.** Split it in two before designing anything: which part is
decidable from the principal alone, and which part needs the call's arguments.
That split *is* the design. The first part may drive layer 1; the second part can
only ever be layer 3.

**No model was found**, and the survey's three negatives support it — nothing can
refuse an authenticated caller, no query filters by a caller attribute, and the
deployment itself is the boundary. Then this pass ships the census, the strategy
and the descriptions, and **stops**. It does not invent one.

Produce three things:

- **The negative as evidence** — the patterns searched, the hit counts, and the
  walk from entry point to executing tool, in the evidence-not-conclusions shape
  `RESEARCH-BRIEFS.md` sets for every return.
- **A trigger** — the second tenant, the first tool that writes, the first
  externally-facing caller.
- **One seam** — a single explicit boundary the tool layer calls before executing
  anything, whose implementation today permits everything and logs the call. One
  seam and no policy. That is a day of work that makes the future model a change
  in one file instead of a change in forty.

Standing up roles, a policy language or an entitlements service so that a
tool-search pass has something to scope to is scope this pass did not earn, and
it produces a permission model whose only user is the document that proposed it.

## The gap this all has to be designed around

**Identity is available at the request edge and gone by the time the model calls
a tool.** That is the normal condition of an agent tool layer, not a defect
somebody introduced.

The reason is structural rather than accidental: the tool is invoked from an
execution loop that was handed a tool name and a JSON object. The loop has no
reason to know a principal exists, and the tool's own signature has nowhere to put
one. Four more reasons stack on top — the turn is streamed or handed to a worker
pool and ambient context does not cross that boundary; the turn is not a request
at all, but a schedule, a queue consumer, a retry or a conversation resumed days
later; the tools are singletons built at startup with a static downstream
credential, a design that has already decided identity does not vary; or a
sub-agent was spawned with a brief and no principal.

Detect it without asking anyone: pick one tool and find whether anything reachable
inside it can name the caller. If the only caller-shaped value in scope arrived as
a tool argument, or there is none, the gap is present. Repeat on the streaming
path and on the resume path; those diverge from the plain path more often than
not.

**Closing it, generically.** Capture the principal at the edge as a *snapshot*,
not a live reference to a request object that will be recycled. Put the snapshot
on whatever object the framework already threads through the invocation, or pass
it explicitly into the turn. Read it at the tool-execution boundary. Two rules
hold whatever the carrier: identity is never reconstructed from the conversation,
and never taken from a tool argument.

**What the snapshot carries, and what it re-reads.** It carries *identity* — who
is asking, and which tenant they are asking within. The *verdict inputs* — roles,
scopes, plan, entitlements — are re-read at the boundary for the call it is about
to run, because they change while a conversation is open and a copy taken at the
edge is a decision frozen at turn start wearing the costume of a fact. Where a
turn is short and the attributes are cheap, copying them is a defensible
optimisation; where a turn is long, or the tools write, the boundary re-reads
them. That distinction is the whole difference between a snapshot and a stale
authorization.

**Where there is no edge to capture from** — a scheduled, queued or retried run —
the authority is decided explicitly: either a service principal with a
deliberately smaller permission set, or the initiating user's identity snapshotted
when the run was scheduled and re-validated when it actually runs. Letting it
inherit whatever the process happens to hold is how a nightly job ends up with
more authority than any human in the system.

## Layer 1 — per-caller discovery, inside the strategy

A `ToolSearchStrategy` sees exactly three things through `ToolSearchRequest`:
`toolExecutionRequest()`, `searchableTools()` and `invocationContext()`. That is
the whole surface, and it is enough.

It is also `@Experimental`, so the filter is written as a thin adapter over a
policy object of your own rather than as logic living inside a library type — the
reasoning, and what an upgrade of it has to re-read, is in
`STRATEGY-AND-DESCRIPTIONS.md`. The practical consequence here is that the tests
below assert against your policy type and keep compiling across an upgrade that
breaks the adapter.

The strategy runs inside the turn and receives the invocation context, so whatever
mechanism your application already uses to reach the caller from inside a turn is
available here. If that mechanism is ambient request scope, verify it survives to
this point — the streaming path and the worker-pool path are exactly where it does
not, and the failure is a strategy that sees no caller and silently falls back to
the whole catalogue.

**Filter `searchableTools()` before scoring. Never the results after.** Two
independent reasons, and either one on its own is sufficient.

*It closes an enumeration oracle.* A refusal must never name a tool the caller was
never offered, because the model narrates it and a hidden catalogue becomes
enumerable through ordinary conversation. A search that ranks over the **whole**
catalogue reopens that oracle through the other door: it returns names the caller
cannot use, the model tells the user about them, and the scoping was decoration.

*It preserves recall for the tools the caller does have.* Scoring the whole
catalogue and discarding afterwards means forbidden tools consume slots in a
five-result window. A tool the caller may legitimately use loses its place to one
they may not, and the visible symptom is a capability that "sometimes doesn't
work" for exactly the callers with the fewest permissions.

**Only the capability half may drive this filter.** Roles, permission strings,
plan entitlements, scopes, and the subject-and-environment half of an attribute
policy answer from the principal alone, which is what a filter built at turn
assembly can know. Relationship and per-object models cannot: rolled up to the
tool they answer *yes* for nearly everyone, and rolled up wrongly they remove a
capability the caller has.

`getToolSearchTools` also receives the invocation context, so even the search
tool's own description can differ per caller. Use that sparingly and for
usefulness rather than for secrecy: it is standing prompt on every turn, and a
description that enumerates what this caller may do is a leak channel with a
larger surface than the one you just closed.

### What this filter cannot narrow: `ALWAYS_VISIBLE`

This filter narrows the tools that search returns. So a tool marked
`SearchBehavior.ALWAYS_VISIBLE` — whose guarantee is precisely that **search
never hides it** — is outside what this filter can reach. It goes in front of
every caller, on every turn, regardless of what the filter would have decided
about any of them, and it does so without a search having been performed at all.

That is what always-visible is *for*: the tool a turn cannot fail to find, the
management tool the model needs in order to reach anything else, the fallback a
graceful failure depends on. `STRATEGY-AND-DESCRIPTIONS.md`, *Always-visible
tools*, carries when to reach for it and the cross-check every marking passes
before the gate — an availability argument and a permissions argument are made in
different files by different people, and **the tool the scope was written to hide
ends up in front of everybody in silence**.

Two consequences for how this pass runs.

**A tool whose `principals` cell holds a real restriction stays unmarked**, and a
tool that must be marked for availability reasons is a tool whose restriction has
to be enforced somewhere else. Gate item 8 in `SKILL.md` is where that check is
shown.

**Somewhere else is layer 3.** An always-visible tool bypasses the discovery
filter, which costs nothing in enforcement terms because the discovery filter was
never enforcement — fact 1 in `SKILL.md` again, arriving from a new direction. The
execution guardrail sees the call whether the model found the tool through a
search, through a marking, or through neither. So the honest reading of a
sensitive tool that has to stay visible is not "the scope has a hole"; it is
"this tool's restriction was always layer 3's job, and marking it visible removes
the layer that was making that comfortable to forget."

### Every cache on this path is keyed by the caller, or it is a leak

The moment the filter exists, the candidate set is a function of the principal.
So a cache of search **results** keyed by the query alone hands the first
caller's candidate set to the second one who asks the same thing. That is the
enumeration channel this layer was built to close, re-opened by a performance
change that reviews as a performance change: nothing throws, nothing is logged,
and the fast path is the wrong answer while the slow path is the right one.

**The rule is one sentence: the key contains everything the filter reads.** If
the filter reads roles, the roles — or a fingerprint of them — are in the key. If
it reads a plan tier, that is in the key. If nobody can enumerate what the filter
reads, the cache cannot be keyed correctly, and *that* is the finding to report
rather than a cache to ship.

Two caches on this path are less obvious than the result cache and leak the same
way.

**A negative cache leaks in reverse.** "This query returned nothing" cached
against the query alone, populated by a caller who may use very little, then
served to a caller who may use a great deal, removes a capability from someone
who has it. It produces the support report that a feature "sometimes doesn't
work", with no error anywhere and no correlation to anything the user did.

**The search tool's own specification is cacheable and caller-varying** the
moment you use `getToolSearchTools`' invocation context to differ it per caller.
A cache in front of it is a cache of standing prompt, which is the most durable
place a wrong answer can land.

**The safe shape, when you do want a cache, is to put it below the filter rather
than in front of it** — over inputs that do not vary by caller at all. The
framework's own default is exactly this shape: a tool's text embeds to the same
vector for everyone, so caching it is caller-independent by construction
(`STRATEGY-AND-DESCRIPTIONS.md` says why that one is not the hazard). Filter
first, then score against caller-independent cached inputs.

**A per-caller cache holds a permission verdict, so its lifetime is a policy
decision**, not a library default — the same clock as the relationship-check
cache above, and decided the same way: how long are you willing to serve a
permission that has been revoked. The honest mitigation is that this is discovery
and never the control, which is what makes a short staleness survivable here and
unacceptable at layer 3. It is a reason to bound the life deliberately, not a
reason to skip the key.

Whatever the shape, the per-caller test at the end of this file runs **with the
cache warm, in both principal orders**. A cold-cache test passes on a cache that
is broken in exactly this way.

**What this layer buys**: accuracy and tokens. What it does not buy is on the next
two headings.

## Layer 2 — prompt reinforcement, and why it is not enforcement

Telling the model in the standing prompt what this caller may and may not use is
worth doing. It reduces attempts that would be refused, it makes the model's
explanations to the user more accurate, and it costs a line.

It is not a control. The model is not a permission system and is not obliged to
obey; injected text competes for the same attention (`prompt-injection-layers`
owns that fight); and a plan that ships layers 1 and 2 and calls the tool layer
scoped has changed what the model believes and left the executor map exactly as
wide as it was.

One rule about the wording, and it is the same rule as the refusal wording below:
**keep it scoped to the specific capability.** A broad line — "you are not
permitted to use tools" — generalises, persists in the conversation, is re-read on
every following turn, and leaves the agent degraded for the rest of the session
over one restriction that applied to one tool.

## Layer 3 — the execution guardrail, which is the enforcement

Five positions can hold a check. The column that decides the architecture is
whether the position still holds when the model emits a tool call **nobody offered
it**.

| Position | Can decide | Holds against an unoffered call? |
|---|---|---|
| Request edge | whether this caller may talk to the agent at all | **No** — it finished before any tool ran |
| Turn assembly (layer 1) | which tools are *offered* | **No** — it changes what the model is shown; the executor map it would have to narrow is not something the search SPI can reach |
| **Tool-execution boundary** | both questions, on every call without exception | **Yes** — the only agent-level position that does |
| Inside one tool | whatever that tool's author remembered | yes for that tool, nothing for the next one added |
| Downstream system | the real answer on the real resource | yes — but about the **credential presented** |

**A model emits a name nobody offered it for five ordinary reasons.** It saw the
name earlier in the same conversation, before the visible set changed. The name
arrived in text it read — a retrieved document, a tool result, another agent's
output. The name was guessable, because tool names are written to be predictable
and `get_user`, `delete_account`, `refund_order` all follow from the ones that
*were* shown. The conversation was resumed, replayed or summarised and a name
survived into the new context. Or — the one that needs no adversary at all — **the
caller's permissions changed mid-conversation**: a plan downgraded, a role
revoked, a trial expired. Nothing hallucinated anything; the offered list was
correct when it was built and is wrong now.

That last reason alone kills *check once when the turn starts* as an enforcement
strategy, independently of everything else. A verdict computed when the turn began
is already stale by the tenth tool call inside that same turn, and the execution
boundary is the only position that sees the tenth call.

How wide that staleness window is comes straight from the disclosure-lifetime
observation in `SKILL.md`, Step 2. Where a search's result reaches only the next
model call, the window is short. Where the disclosure survives across turns, it
is as long as the conversation, and there is a second thing to establish while
you are measuring: whether the filter re-runs over a disclosure the model already
has. A visible set rebuilt from an earlier search's **output** was filtered when
that search ran and has not been filtered since — which is a per-caller filter
that is correct every time it executes and stale in the history regardless. Do
not design around a guess here; it is observable the same way the lifetime was,
by narrowing a caller's permissions between two turns of one conversation and
serializing the specifications on the turn after.

**Where neither could be measured**, take the window as **as long as the
conversation** and design against that. It is the assumption that costs nothing
when it turns out to be wrong: a guardrail written for a long window is still
correct on a short one, while the reverse fails exactly on the turns where a
permission was revoked and the conversation kept going. `SKILL.md`, Step 2
carries the same default for `maxResults`, and for the same reason.

**What the guardrail is.** One interceptor around every tool invocation, which
sees the tool name, the arguments and the principal, and answers both questions:
capability from the principal, object from the arguments. It is where the
`principals` column of the census becomes real, and it is the only place the
`unknown` rows are dangerous, because an interceptor that permits what it does not
recognise inherits every gap in the census.

**Why per-tool checks are not it.** They are locally correct and structurally
weak, for two separable reasons: a newly added tool starts with no check because
nothing forces one, and the set of tools that check is not enumerable by anybody —
there is no single place to look to answer "which tools enforce something", so a
review approves a tool layer without noticing the one tool that does not. Both
reasons weaken when tools route through **one shared service** that checks: a new
tool written the obvious way inherits the control, and the set becomes enumerable
by looking at that service's callers. Materially stronger than per-tool checks,
still short of the boundary, because a tool that reaches its data another way
bypasses it in silence.

**Downstream is the strongest backstop and the easiest to over-trust.** With the
caller's own token forwarded it is genuine end-to-end enforcement and everything in
the agent is defence in depth on top. With a shared service credential — the common
case — it enforces the *service's* permissions, a superset of every caller's, and
the agent layer is the only thing standing between one caller and everything the
service is allowed to do. Establish which of the two you have before crediting
downstream with anything.

**Give each layer a different question.** Layer 1 asks what this caller should be
led toward, the boundary asks whether this call may run right now with these
arguments, downstream asks whether it is permitted on the real resource. Three
copies of one coarse capability check at three levels is not depth; it is one
control with three places to forget it.

**The last thing to say out loud:** the only position that holds universally is
also the position most likely to have lost the principal, for exactly the reasons
in the gap section above. Plan the guardrail and the principal's carrier
together.

## What a refusal returns to the model

A denial is a **return value, not a thrown exception** — that rule belongs to
`agentic-tool-boundary` and does not change here. What follows is the *content*.

Three properties it must have.

**Terminal.** Say the call will not succeed if repeated. A model that reads
`Error: 403` tries again with different arguments, then a different tool, then
several more attempts, and eventually reports an outage to a user who does not
have one.

**Actionable for the turn.** Say what to do instead: answer without this tool, or
tell the user the specific thing *they* can do. A denial with no alternative
produces a stall or an invention.

**Attributed to the account, not to the agent.** "You do not have permission"
reads to a model as a statement about itself, and it relays "I don't have
permission to do that" — a different and confusing claim in front of a user who
does have it, or who does not and is not being told so. Write the message about
the caller's account.

**The asymmetry that decides the wording: an entitlement denial may be specific, a
security denial must be generic.** Naming the plan is the entire point of an
entitlement refusal — the user can act on it and it was already on the pricing
page. Naming the role, scope, group or policy behind a security refusal buys
nothing and hands out three things: internal structure, an escalation target, and,
in any system with a grant-shaped tool at all, a next step the model may
cheerfully attempt on the user's behalf.

| Never in the message | What it costs |
|---|---|
| a tool the caller was never offered, named in a refusal | confirms the tool exists; the model repeats it to the user, and the hidden catalogue becomes enumerable through conversation |
| the predicate — role, scope string, plan tier, policy id, group | internal structure, plus a precise hint about what to acquire |
| the difference between "does not exist" and "exists but is not yours" | the classic existence oracle, inherited whole; under an object denial return the same answer for both |
| any other principal — an owner's name or account | a refusal should never introduce a person the caller had not already named |
| which argument was refused | a model that learns which field was refused will vary it, and a refusal it can retry into success is a probe with a helpful narrator |

Two properties of the medium. **The denial persists**: it enters the conversation
history, is re-read every following turn, and may be folded into a summary — so
keep it scoped to the one call. And **assume the user reads it**, because the model
paraphrases it into the answer. Everything the message may not carry still belongs
in the audit log — principal, tool, arguments, predicate, decision — which is where
that detail is useful and where it does no harm.

## Proving each layer

| Layer | The test |
|---|---|
| Discovery filter | the same query under two principals returns different candidate sets, and the smaller is a strict subset of the larger |
| Discovery filter, oracle | no result returned to a caller names a tool their principal may not run — asserted over the whole query set, not sampled |
| Discovery filter, cached | the first row again with every cache on the path **warm**, run in both principal orders; passing in one order only is a cache keyed by the query |
| Discovery filter, always-visible | enumerate the tools marked `ALWAYS_VISIBLE` and assert that none of them carries a restriction in the census's `principals` column. The filter cannot narrow these and never will, so this is an assertion about the *marking list*, not about the filter's output — and where a restricted tool has to stay marked for availability reasons, the guardrail row below is the test that speaks for it |
| Prompt reinforcement | the rendered standing prompt names only capabilities this caller has, and no tool name they may not use |
| Execution guardrail | drive a tool name that search never returned, and one that layer 1 filtered out, straight into the execution path; both are refused |
| Guardrail, mid-turn | change the principal's permissions between two calls inside one turn; the second call is refused |
| Guardrail, across the disclosure window | narrow a caller's permissions between two turns of one conversation; a tool disclosed to them before the change is refused at execution afterwards, whether or not it is still visible |
| Refusal wording | the message is terminal, actionable, about the account, and contains none of the five rows above |

The execution-guardrail row is the one that matters, and it is the only test in
this pass that distinguishes a scoped tool layer from a tool layer whose model
has merely been told less. Write it first. If it cannot be written — because identity does not
reach the execution boundary — that is the finding, and it belongs at the gate in
those words, not in a footnote.
