---
name: agentic-service-composition
description: Wire an agent runtime out of one service per role. Use when a single service both classifies and answers, when a model name appears in application code, when deciding where a guardrail, listener or tool provider attaches — or when one never seems to run, when a provider's prompt cache is not hitting, or when something expensive is built on first use. For whether a cheap classifier should run in front at all, use llm-triage-gate.
---

# Composing an agent runtime

The unit of composition is the **role**: a named job — classify, answer,
summarise, embed — owning a model handle, a prompt, a memory and a tool set.

## 1. One service per role

**One service object per role.** Capability sets do not compose — a merged service
inherits the union. A classifier reached through the answerer's service carries tool
schemas it never calls: at ~80 tokens a schema (`progressive-tool-disclosure`), a dozen
is ~1k tokens per classification, the cost a gate exists to avoid. It also inherits the
answerer's memory, so the classification returns next turn as if the user had said it —
**ASI06 Memory & Context Poisoning**, no attacker involved.

Write the matrix down. The **Never** column catches the drift, one failure a cell:

| Role | Memory | Tools | Retrieval | Never — and what goes wrong |
|---|---|---|---|---|
| classifier | none | none | none | **answers an in-scope turn, or acts** — it would answer with no tools, no memory and no retrieval, which is to say from nothing |
| answerer | window | disclosed set | routed | **writes its own system prompt** — text it read this turn, tool output included, then sets next turn's standing instructions, and the cached prefix of §4 goes with it |
| summariser | none | none | none | **calls a tool** — it is handed a transcript and retains nothing between runs; a tool is a second execution path over that untrusted text, reached without the answerer's guardrail chain |
| embedder | none | none | n/a — it *is* retrieval | **resolves to a model other than the one that built the index** — neighbours come from a different vector space, dimensions often match, nothing errors, and the threshold measured in `retrieval-that-earns-its-place` is measuring noise |

**A role earns existence when the model call differs** — model, parameters, capability
set — **or when a number must be independently decomposable.** A second prompt is not a
second role; it becomes one under that clause, the day it arrives with its own capability
set or its own cost line, and forty roles nobody reads is the same failure from the other
end. **A sub-agent is a role with its own assembly**, never a reuse of the parent's
assembled agent — that inherits exactly what this section forbids merging
(`subagent-context-isolation`: when one is worth its cost).

## 2. One registry owns `role → model`, and roles are named for jobs

```
BAD   chat("provider-small-2.5", judgePrompt)
BAD   models.get(Role.FLASH)            // a model id in costume
GOOD  models.get(Role.CLASSIFY)
```

Addressing a model by role at all is `llm-cost-observability`'s rule; composition adds
where the map lives and what a role may be called. **A role is a job, not a model tier.**
A role named `flash` satisfies the borrowed rule completely and still breaks the day it
changes vendor — the tag stays stable, the thing it names does not, and the migration
reads as a latency regression in `flash`. Name a role for the work it decides, and use
that one literal string in configuration, metrics, logs and evals alike.

**One component owns `role → (provider, model id, temperature, timeout, output cap,
optional fallback provider + id)`**, read from configuration, handing out clients by role
name. The fallback is a field on that tuple, not a sibling role — the same job on another
vendor — and written any other way it is the migration bug above, hiding until the primary
rate-limits, mid-incident. A model name in application code puts it everywhere a model is
chosen — clients, tests, fixtures, the eval harness — and one is missed the day you
migrate, leaving the summariser on the expensive model for months.

**Two roles on one model id is not redundancy** — it is the seam that lets one move
vendor later, and until then it keeps their latency and spend separately measurable.

**Resolve every role at startup, and throw on an unknown one — naming the roles that are
configured.** A silent fallback to a default client is how a typo'd role name runs the
expensive model under the cheap model's name, discovered on the invoice, after every
latency graph you drew was of a different model than you knew.

## 3. Attach at the layer the framework actually calls

A control attached one layer off does not error — it silently never runs. Four terms:

| Layer | Fires | What attaches |
|---|---|---|
| invocation | once per user turn | **guardrails** — checks run around an invocation |
| request assembly | when the turn's request is built | **tool providers** — which tools exist this turn |
| model call | every round trip, including inside the tool loop | **listeners** — observers of one round trip |
| tool execution | every tool call | **decorators** — what happens when one runs |

```
BAD   input guardrail → [ model ⇄ tool ⇄ model ⇄ tool ⇄ model ] → output guardrail
                         └──────── never inspected ─────────┘
GOOD  input guardrail → [ model ⇄ decorator ⇄ tool ⇄ … ] → output guardrail
```

What the diagram cannot show: indirect prompt injection — instructions planted in
data the agent fetches — arrives *as tool output*, inside the loop, where nothing
firing once per turn reaches it. The control that removes **ASI01 Agent Goal
Hijack** is therefore a **decorator**; calling it a guard is how it ends up
attached one layer up, inspecting nothing. Three ways a control silently does nothing:

- **The overload the framework does not call** — `execute` beside
  `executeWithContext`; only one is ever called. Read the call site.
- **The short-circuit path.** A hallucinated tool name resolves to no executor, so
  every decorator is skipped for exactly the input an attacker steers — and the
  default handler throws, turning a model typo into a 500.
- **The provider that cannot disclose.** Resolved at startup or at the invocation layer,
  it returns one fixed tool set — activation changes nothing the model sees, no test fails.

**Listeners attach where the model client is built, not on the service wrapper**, on every
role, embedder and summariser included. A wrapper-level listener misses transport retries,
every tool round trip after the first, and the summariser's own calls; an invocation-layer
one reports one event for a six-call turn. An unlistened role is on no dashboard — the gap
behind **ASI10 Rogue Agents**.

**Chain order is `prompt-injection-layers`' subject** (and pre-filters before the model
call are `llm-triage-gate`'s); **one ordering constraint is composition's own.** An
output guardrail that must see a whole response cannot run on a token stream without
buffering it, so settle whether a role streams before its chain is written.

**Assert that each control ran, not that the guarded behaviour looks right** — the
undecorated path passes the behaviour test too — **then snapshot the whole assembly at
boot**, because every defect above is a wiring change no per-control test observes. Emit
per role the model handle, memory kind, tool count, retrieval mode (`none | always |
routed`), the guardrail chain and the decorator chain **in order**, the listener count
and a hash of the standing prompt — then assert it against a checked-in expected shape.

§1's **Never** column then holds by construction, cell by cell: tool count zero is the
summariser's "never calls a tool" *and* the **or acts** half of the classifier's, a role
with no tools having nothing to act with; a matching hash is "never writes its own
prompt"; the embedder's holds once the index records the model id that built it and the
snapshot asserts the query handle matches — pin the query side alone and a role emits the
expected handle over another model's index. Left over is the classifier's other half,
**answers an in-scope turn**: behaviour, not wiring, so an eval case (`agentic-evals`).
The hash catches a prompt someone *edited*, in the diff that caused it; §4's catches one
that *varies at runtime*.

## 4. The standing prompt is byte-stable

**First check the floor.** A provider's automatic cache ignores a prefix below a
documented minimum, and the minimum is not monotonic even within one vendor — one
family runs 512, 1024, 2048 and 4096 tokens by model. Above it, a read bills at
~**0.1× input** against a **1.25× write** on the short TTL, so two turns pay for it;
the long TTL doubles the write and needs three. (That vendor's published rates, read
2026-08-14 — look up your own.) The cache keys on an **exact** prefix: one differing
byte and the whole prefix bills at full price, every turn, for the deployment's life.

**Below the floor, byte-stability buys nothing** — stop there, or clear the floor on
purpose: move per-turn material above the conversation — few-shot examples, the scope
description, a tool set you were disclosing lazily — until the prefix passes. Those
tokens bill once at the write rate and ~0.1× per read after, so it pays where one prefix
serves many turns and loses outright for a role that sees one turn per prefix.

```
[ standing prompt + tool schemas ]   assembled once, byte-identical every turn
[ conversation                   ]   append-only
[ turn context + user message    ]   language, hints, chunks   <-- varies
```

**Varies per turn.** A timestamp — `Today is 2026-08-14 14:22:07` changes the prefix
every second, so the cache never hits, once, ever. A system message re-rendered with a
name or a routing hint. An edit to an earlier message, which invalidates the prefix from
there on: schedule rewrites, do not run them continuously. Fix: assemble once at startup,
carry per-turn material in the user message, and test two turns byte-identical — on the
bytes sent, not the template that produced them.

**Varies per process.** A prompt or tool list rendered from a hash-ordered collection;
locale- or timezone-dependent formatting. These are stable *within* a process, so that
two-turn diff **passes**, and they differ across replicas and restarts: nothing breaks,
the hit rate just falls as you scale out. Fix: sort before rendering, format with a fixed
locale, then diff a second process against the first.

**Find where your framework serializes tool schemas relative to the system
prompt.** If they precede it, disclosing a tool set mid-conversation changes the
prefix and the turn after every activation pays full price — the real cost of
progressive disclosure, to be measured rather than assumed away.

## 5. Warm eagerly, and gate readiness

**Latency is the obvious reason and the weaker one.** Lazy construction bills one unlucky
user on a request indistinguishable from every other: a quantized in-process embedding
model, measured here at **5.7 s** to load, lands at p99.9, reads as a network blip, and is
never diagnosed — measure your own. Nor is it rare: once per deploy, then per replica,
then on every autoscale event. Startup time is still a budget, though — when eager warmup
pushes boot past what the orchestrator tolerates, raise the probe's initial delay, because
going lazy to survive a liveness probe trades a deploy-time problem for a production one.

**The stronger reason is that construction is when configuration is validated.** A missing
credential or an unknown model name makes an eagerly built process refuse to start, and
the deploy rolls itself back; built lazily, each is a 500 on the first request needing
that role, days after the deploy that caused it if the role is rare. Keep readiness red
until the eager work finishes.

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
4. Find each role's cacheable-prefix floor; diff the standing prompt — two turns, then
   two processes, on the bytes sent — only for the roles that clear it, and for one that
   does not, decide whether material moves in on purpose.
5. For everything expensive: who pays the first time, does a missing dependency
   stop the deploy, and does a wiring change fail the assembly snapshot?
