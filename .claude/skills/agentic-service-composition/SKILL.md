---
name: agentic-service-composition
description: Wire an agent runtime out of one service per role. Use when a single service both classifies and answers, when a model name appears in application code, when deciding where a guardrail, listener or tool provider attaches — or when one never seems to run, when a provider's prompt cache is not hitting, or when something expensive is built on first use.
---

# Composing an agent runtime

The unit of composition is the **role**: a named job — classify, answer,
summarise, embed — owning a model handle, a prompt, a memory and a tool set.

## 1. One service per role

**One service object per role.** Capability sets do not compose — a merged
service inherits the union. A classifier reached through the answerer's service
carries tool schemas it will never call — roughly a thousand tokens per
classification (`progressive-tool-disclosure`), the cost a gate exists to avoid —
and inherits the answerer's memory, so the classification returns next turn as if
the user had said it: **ASI06 Memory & Context Poisoning**, no attacker involved.

Write the matrix down. The **Never** column catches the drift, one failure a cell:

| Role | Memory | Tools | Retrieval | Never — and what goes wrong |
|---|---|---|---|---|
| classifier | none | none | none | **answers an in-scope turn, or acts** — it would answer with no tools, no memory and no retrieval, which is to say from nothing |
| answerer | window | disclosed set | routed | **writes its own system prompt** — text it read this turn, tool output included, then sets next turn's standing instructions, and the cached prefix of §4 goes with it |
| summariser | reads the transcript | none | none | **calls a tool** — a second execution path over the same untrusted transcript, reached without the answerer's guardrail chain |
| embedder | none | none | n/a — it *is* retrieval | **resolves to a model other than the one that built the index** — neighbours come from a different vector space, dimensions often match, nothing errors, and the threshold measured in `retrieval-that-earns-its-place` is measuring noise |

**A role earns existence when the model call differs** — model, parameters,
prompt, capability set — **or when a number must be independently decomposable.**
A second prompt is not a second role; forty roles nobody reads is the same failure
from the other end. **A sub-agent is a role with its own assembly**, never a reuse
of the parent's assembled agent — that inherits exactly what this section forbids
merging (`subagent-context-isolation`: when one is worth its cost).

## 2. Address a model by role, never by name

```
BAD   chat("provider-small-2.5", judgePrompt)
GOOD  models.get(Role.CLASSIFY)
```

One component owns `role → (provider, model id, temperature, timeout, output
cap)`, reads it from configuration, and hands out a client by role name; a
fallback provider is another optional role in that map. A model name in
application code puts that name everywhere a model is chosen — clients, tests,
fixtures, the eval harness — and one of those is missed the day you migrate,
leaving the summariser on the expensive model for months.

**A role is a job, not a model tier.** Tagging by role rather than model id is
`llm-cost-observability`'s rule; a role named `flash` satisfies it completely and
still breaks the day that role changes vendor — the tag stays stable, the thing it
names does not, and the graph reads as a latency regression in `flash` rather than
a vendor migration. Name a role for the work it decides, and use that one literal
string in configuration, metrics, logs and evals alike.

**Two roles on one model id is not redundancy** — it is the seam that lets one
move vendor later, keeping latency and spend separable (`llm-cost-observability`).

**Resolve every role at startup, and throw on an unknown one — naming the roles
that are configured.** A silent fallback to a default client is how a typo'd role
name runs the expensive model under the cheap model's name, discovered on the
invoice, after every latency graph you drew was of a different model than you knew.

## 3. Attach at the layer the framework actually calls

A control attached one layer off does not error — it silently never runs. Four
terms, each belonging to exactly one layer:

| Layer | Fires | What attaches |
|---|---|---|
| invocation | once per user turn | **guardrails** — checks run around an invocation |
| model call | every round trip, including inside the tool loop | **listeners** — observers of one round trip |
| tool execution | every tool call | **tool providers** ("which tools exist this turn") and **decorators** ("what happens when one runs") |

```
BAD   input guardrail → [ model ⇄ tool ⇄ model ⇄ tool ⇄ model ] → output guardrail
                         └──────── never inspected ─────────┘
GOOD  input guardrail → [ model ⇄ decorator ⇄ tool ⇄ … ] → output guardrail
```

What the diagram cannot show: indirect prompt injection — instructions planted in
data the agent fetches — arrives *as tool output*, inside the loop. A guardrail
cannot see it, because a guardrail fires once per turn, before the loop opens and
after it closes; the control that removes **ASI01 Agent Goal Hijack** is therefore
a **decorator**, and calling it a guard is how it ends up attached one layer up
and inspecting nothing. Two ways an attached control still never runs:

- **The overload the framework does not call** — in one framework, `execute`
  beside `executeWithContext`; only one is ever called. Read the call site.
- **The short-circuit path.** A hallucinated tool name resolves to no executor, so
  every decorator is skipped for exactly the input an attacker steers — and the
  default handler throws, turning a model typo into a 500.

**Listeners attach where the model client is built, not on the service wrapper**,
and on every role including the embedder and summariser. A wrapper-level listener
misses transport retries, every tool round trip after the first, and the
summariser's own calls; an invocation-layer one reports one event for a six-call
turn. An unlistened role is on no dashboard — the gap behind **ASI10 Rogue Agents**.

**What order the chain runs in is `prompt-injection-layers`' subject; one ordering
constraint is composition's own.** An output guardrail that must see a whole
response cannot run on a token stream without buffering it, so whether a role
streams decides where its output control can live — settle that per role before
the chain is written. (Pre-filters *before* the model call: `llm-triage-gate`.)

**Assert that each control ran, not that the guarded behaviour looks right** — the
undecorated path passes the behaviour test too — **then snapshot the whole
assembly at boot**, because every defect above is a wiring change no per-control
test observes. Emit per role the model handle, memory kind, tool count, retrieval
mode (`none | always | routed`; a boolean cannot express the answerer's row), the
guardrail chain and the decorator chain **in order**, the listener count and a
hash of the standing prompt — then assert it against a checked-in expected shape.

Three of §1's **Never** cells then hold by construction: tool count zero is "never
calls a tool", a matching hash is "never writes its own prompt", and the embedder's
holds once the index records the model id that built it and the snapshot asserts
the query handle equals that — pin only the query side and a role emits exactly the
expected handle while querying another model's index. The fourth, a classifier that
answers, is behaviour rather than wiring: an eval case (`agentic-evals`). The hash
catches a standing prompt someone *edited*, in the diff that caused it; §4's diff
catches one that *varies at runtime*.

## 4. The standing prompt is byte-stable

**First check the floor.** A provider's automatic cache ignores a prefix below a
documented minimum — roughly 1k tokens, and not monotonic even within one vendor:
one provider's current family runs 512, 1024, 2048 and 4096 tokens by model (read
2026-08-14). Above yours a read bills at ~**0.1× input** against a 1.25× write, so
two turns pay for it — and the cache keys on an **exact** prefix: one differing byte
and the whole prefix is billed at full price, every turn, for the deployment's life.

**Below the floor, byte-stability buys nothing** — stop there and spend the effort
on the roles that clear it. Or clear it on purpose: move per-turn material above
the conversation — few-shot examples, the scope description, a tool set you were
disclosing lazily — until the prefix passes. Those added tokens bill once at 1.25×
and then ~0.1× per read, so it pays where one prefix serves many turns and loses
outright for a role that sees about one turn per prefix.

```
[ standing prompt + tool schemas ]   assembled once, byte-identical every turn
[ conversation                   ]   append-only
[ turn context + user message    ]   language, hints, chunks   <-- varies
```

**Varies per turn.** A timestamp — `Today is 2026-08-14 14:22:07` changes the
prefix every second, so the cache never hits, once, ever. A system message
re-rendered with a name or a routing hint. An edit to an earlier message, which
invalidates the prefix from there on: schedule rewrites, do not run them
continuously. Fix: assemble once at startup, carry per-turn material in the user
message, and test two turns byte-identical — on the bytes sent, not the template
that produced them, since the template is where the timestamp is absent.

**Varies per process.** A prompt or tool list rendered from a hash-ordered
collection; locale- or timezone-dependent formatting. These are stable *within* a
process, so that two-turn diff **passes**, and they differ across replicas and
restarts: nothing breaks, the hit rate just falls as you scale out. Fix: sort
before rendering, format with a fixed locale, then diff a second process against
the first.

**Find where your framework serializes tool schemas relative to the system
prompt.** If they precede it, disclosing a tool set mid-conversation changes the
prefix and the turn after every activation pays full price — the real cost of
progressive disclosure, to be measured rather than assumed away.

## 5. Warm eagerly, and gate readiness

**Latency is the obvious reason and the weaker one.** Lazy construction bills one
unlucky user on a request indistinguishable from every other: a quantized
in-process embedding model, measured at **5.7 s** to load, lands at p99.9, reads
as a network blip, and is never diagnosed. Measure yours — and note it does not
become rare: once per deploy, then per replica, then on every autoscale event.

**The stronger reason is that construction is when configuration is validated.** A
missing credential or an unknown model name makes an eagerly built process refuse
to start, and the deploy rolls itself back; built lazily, each is a 500 on the
first request needing that role — days after the deploy that caused it, if the
role is rare. Keep readiness red until the eager work finishes.

**Startup time is a budget, not a free good.** When eager warmup pushes boot past
what the orchestrator tolerates, raise the probe's initial delay — going lazy to
survive a liveness probe trades a deploy-time problem for a production one.

**Decide per expensive thing whether its absence stops the deployment, and
announce every degraded mode by name at boot** — *"no judge-fallback configured; a
rate-limited judge will fail open."* A runtime that silently falls back to
in-memory conversation storage keeps the health check green while every
conversation is lost: the composition-level **ASI08 Cascading Failures**.

## Reviewing an existing runtime

1. Name the roles. Where one service serves two, whose memory and tool set does
   it carry on the turn it should not?
2. Grep for a model name in application code, then read the role names — one
   named for a size or a vendor is a model id in costume.
3. For each guardrail, listener, provider and decorator: name the layer that
   calls it, read the chain's order, point at the test asserting it *ran*.
4. Diff the standing prompt across two turns, then two processes, on the bytes sent.
5. For everything expensive: who pays the first time, does a missing dependency
   stop the deploy, and does a wiring change fail the assembly snapshot?
