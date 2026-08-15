---
name: agentic-service-composition
description: Wire an agent runtime out of one service per role. Use when a single service both classifies and answers, when a model name appears in application code, when deciding where a guardrail, listener or tool provider attaches — or when one never seems to run, when a provider's prompt cache is not hitting, or when something expensive is built on first use.
---

# Composing an agent runtime

The unit of composition is the **role**: a named job — classify, answer,
summarise, embed — owning a model handle, a prompt, a memory and a tool set.

## 1. One service per role

**One service object per role.** Capability sets do not compose — a merged
service inherits the union. A classifier invoked through the answerer's service
carries tool schemas it will never call: a dozen disclosed tools at ~80 tokens
each (`progressive-tool-disclosure` measures this) is ~1,000 tokens on every
classification, and the gate existed to avoid exactly that cost. It inherits the
answerer's memory too, so the classification comes back next turn as if the user
had said it — **ASI06 Memory & Context Poisoning**, with no attacker involved.

Write the matrix down. The "never" column is what catches the drift:

| Role | Memory | Tools | Retrieval | Never |
|---|---|---|---|---|
| classifier | none | none | none | answers the user, or acts |
| answerer | window | disclosed set | routed | writes its own system prompt |
| summariser | reads the transcript | none | none | calls a tool |
| embedder | none | none | none | sees a raw user message |

**A role earns existence when something about the model call differs** — model,
parameters, prompt, capability set — **or when a number must be independently
decomposable.** A second prompt is not a second role; forty roles nobody reads is
the failure at the other end of the same axis.

**A sub-agent is a role with its own assembly**, never a reuse of the parent's
assembled agent, which inherits exactly what this section forbids merging
(`subagent-context-isolation` covers when one is worth its cost).

## 2. Address a model by role, never by name

```
BAD   chat("provider-small-2.5", judgePrompt)
GOOD  models.get(Role.CLASSIFY)
```

One component owns `role → (provider, model id, temperature, timeout, output
cap)`, reads it from configuration, and hands out a client by role name; a
fallback provider is another optional role in that map. A model name in
application code puts that name in eight places, one of which is missed the day
you migrate, leaving the summariser on the expensive model for months.

**A role is a job, not a model tier.** Name roles for the work — `judge`, `agent`,
`summarizer` — never for size or vendor, and use that one literal string in
configuration, metrics, logs and evals alike. A role called `flash` is a model id
in costume: it renames the coupling instead of removing it, and the day that role
changes vendor every metric and log is lying.

**Two roles on one model id is not redundancy** — it is the seam that lets one
move vendor later without touching code, and it keeps their latency and spend
separately decomposable (see `llm-cost-observability`).

**Resolve every role at startup, and throw on an unknown one — naming the roles
that are configured.** A silent fallback to a default client is how a typo'd role
name runs the expensive model under the cheap model's name, discovered on the
invoice, after every latency graph you drew was of a different model than you knew.

## 3. Attach at the layer the framework actually calls

Four terms. A **guardrail** is a check the framework runs around an invocation; a
**listener** observes one model round trip; a **tool provider** answers "which
tools exist this turn"; a **decorator** answers "what happens when one runs". A
control attached one layer off does not error — it silently never runs.

| Layer | Fires | What attaches |
|---|---|---|
| invocation | once per user turn | input and output guardrails |
| model call | every round trip, including inside the tool loop | listeners |
| tool execution | every tool call | tool guards and decorators |

```
BAD   input guard → [ model ⇄ tool ⇄ model ⇄ tool ⇄ model ] → output guard
                     └──────── never inspected ─────────┘
GOOD  input guard → [ model ⇄ tool guard ⇄ tool ⇄ … ] → output guard
```

Invocation-layer guardrails run before the first model call and after the last, so
**tool results pass through neither** — no coverage of indirect prompt injection,
instructions planted in data the agent fetches, which arrive as tool output. A
guard at the tool-execution layer removes **ASI01 Agent Goal Hijack** by that
route. Three ways an attached control still never runs:

- **The overload the framework does not call** — in one framework, `execute`
  beside `executeWithContext`; only one is ever called. Read the call site.
- **The short-circuit path.** A hallucinated tool name resolves to no executor, so
  every decorator is skipped for exactly the input an attacker steers — and the
  default handler throws, turning a model typo into a 500.
- **A provider where a decorator belongs** makes tools silently absent, and the
  model then answers from memory: merely wrong rather than broken.

**Listeners attach where the model client is built, not on the service wrapper**,
and on every role including the embedder and summariser. A wrapper-level listener
misses transport retries, every tool round trip after the first, and the
summariser's own calls; an invocation-layer one under-counts a six-round-trip turn
by six. An unlistened role is on no dashboard — the detection gap behind **ASI10
Rogue Agents**.

**Order the chain: cheap deterministic checks before model-based ones, and any
mutating guardrail before every inspecting one.** A length cap after a model-based
classifier pays a model call to reject an empty message; redaction after an
inspector means the inspector validated bytes that never shipped. And an output
guardrail that must see a whole response cannot run on a token stream without
buffering, so whether a role streams decides where its output control can live.
(Pre-filters *before* the model call belong to `llm-triage-gate`.)

**Assert that each control ran, not that the guarded behaviour looks right** — the
undecorated path passes the behaviour test too. Count invocations, or have the
control record a marker the test reads.

**Then snapshot the whole assembly at boot**, because every defect above is a
wiring change no per-control test observes. Emit per role the model handle, memory
kind, tool count, the guardrail chain **in order** and the listener count, and
assert it against a checked-in expected shape: §1's matrix becomes enforced, and
the change fails in the diff that caused it.

## 4. The standing prompt is byte-stable

**First check the floor.** A provider's automatic cache ignores a prefix below a
documented minimum — roughly 1k tokens, and not monotonic even within one vendor:
one provider's current family runs 512, 1024, 2048 and 4096 tokens by model (read
2026-08-14). Below yours nothing caches, however stable you made it; above it a
read bills at ~**0.1× input** against a 1.25× write, so two turns pay for it. The
cache then keys on an **exact** prefix: one differing byte and the whole prefix is
billed at full price, every turn, for the life of the deployment.

```
[ standing prompt ]   assembled once at startup, byte-identical every turn
[ tool schemas    ]   fixed for the process — unless disclosure changes them
[ conversation    ]   append-only
[ turn context    ]   language, hints, retrieved chunks      <-- varies
[ user message    ]                                          <-- varies
```

**Varies per turn.** A timestamp — `Today is 2026-08-14 14:22:07` changes the
prefix every second, so the cache never hits, once, ever. A system message
re-rendered per turn with a name or a routing hint. An edit to an earlier message,
which invalidates the prefix from there on: schedule rewrites, do not run them
continuously. Fix: assemble once at startup, carry per-turn material in the user
message, and test two turns byte-identical — on the bytes sent, not the template
that produced them, since the template is where the timestamp is absent.

**Varies per process.** A prompt or tool list rendered from a hash-ordered
collection; locale- or timezone-dependent formatting. These are stable *within* one
process, so that two-turn diff **passes**, and they differ across replicas and
restarts: nothing breaks, the hit rate just falls as you scale out. Fix: sort
before rendering and format with a fixed locale — then render in a second process
and diff against the first.

**Find where your framework serializes tool schemas relative to the system
prompt.** If they precede it, disclosing a tool set mid-conversation changes the
prefix and the turn after every activation pays full price — the real cost of
progressive disclosure, to be measured rather than assumed away.

## 5. Warm eagerly, and gate readiness

**Latency is the obvious reason and the weaker one.** Lazy construction bills one
unlucky user, on a request indistinguishable from every other: an embedding model
taking ~6 s to load (illustrative — measure yours) makes one request 6 s slower at
p99.9, where it reads as a network blip and is never diagnosed. And it never
becomes rare — once per deploy, then per replica, then on every autoscale event.

**The stronger reason is that construction is when configuration is validated.**
Given a missing credential or an unknown model name, an eagerly built process
refuses to start and the deploy rolls itself back; built lazily, each is a 500 on
the first request needing that role — days after the deploy that caused it, if the
role is rare. Keep readiness red until the eager work finishes.

**Startup time is a budget, not a free good.** When eager warmup pushes boot past
what the orchestrator tolerates, raise the probe's initial delay — going lazy to
survive a liveness probe trades a deploy-time problem for a production one.

**Decide per expensive thing whether its absence stops the deployment, and
announce every degraded mode by name at boot** — *"no judge-fallback configured; a
rate-limited judge will fail open."* A runtime that silently falls back to
in-memory conversation storage keeps the health check green while every
conversation is lost: the composition-level form of **ASI08 Cascading Failures**.

## Reviewing an existing runtime

1. Name the roles; where one service object serves two, ask whose memory and tool
   set it carries on the turn it should not.
2. Grep for a model name in application code, then read the role names — one named
   for a size or a vendor is a model id in costume.
3. For each guardrail, listener, provider and decorator: name the layer the
   framework calls it from, read the chain's order, point at the test asserting it
   *ran*.
4. Diff the standing prompt across two turns, then across two processes, on the
   bytes as sent.
5. List everything expensive to construct: who pays the first time, does a missing
   dependency stop the deploy or get announced at boot, and does a wiring change
   have to update a checked-in snapshot of the assembly?
