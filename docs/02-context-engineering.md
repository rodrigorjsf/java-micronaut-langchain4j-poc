# 2 · Context engineering, applied

Every token in a prompt is paid for on the turn it appears and, if it lands in
memory, on every turn after. This chapter is what that constraint did to the
design.

Two facts frame it.

**Recall degrades with input length — on every model, even on trivial tasks.**
Chroma's context-rot study found this across all 18 models it tested, and found
focused ~300-token prompts beating full ~113 000-token ones on conversational QA.
Liu et al. (TACL 2024) measured a **10–20% drop** when the relevant information
sits in the middle of a long prompt rather than at either edge. Levy et al. (ACL
2024) measured reasoning accuracy falling from **0.92 to 0.68** at 3 000 tokens of
input — and found that padding with *exact duplicates* still degrades it.

**A bigger context window does not fix any of that.** It is more RAM on a machine
with a memory leak.

So the budget here is self-imposed: **24 000 tokens**, far below what the model
would accept.

## Where the tokens go

| Region | Budget | Paid |
|---|---|---|
| System prompt | ~1 500 | every turn, unchanged |
| Skills index | ~970 at 12 skills | every turn, unchanged |
| Retrieved content | 0–1 000 | only on routed turns |
| Conversation | up to ~14 400 before compaction | every turn, growing |
| This turn's tool results | ≤ 32 KB per call, ~8k tokens | this turn, then in memory |
| Reserve for the answer | ~4 000 | — |

The first two rows are the *standing* cost — the number that multiplies by every
turn of every conversation, so it gets the most attention.

## Lever 1 — progressive tool disclosure

50+ tools. Declared normally, that is roughly 80 tokens of schema each, ~4000
tokens standing, and a model choosing from a list of fifty.

Skills replace that with names and one-line descriptions. **Measured: 81 tokens
per skill.** Twelve skills is ~970 tokens, and the model chooses from twelve.

```
before activation:  activate_skill, read_skill_resource
after  activation:  + that skill's 4–10 tools, and its full instructions
```

The mechanism and its two silent failure modes are in
[ADR 0005](adr/0005-progressive-tool-disclosure-through-skills.md); the general
version is in
[`.claude/skills/progressive-tool-disclosure`](../.claude/skills/progressive-tool-disclosure/SKILL.md).

## Lever 2 — the stable prefix

A provider's automatic prompt cache keys on an **exact** prefix. Anything that
varies per turn must therefore sit after everything that does not.

```
[ system prompt ]     assembled once at startup, byte-identical every request
[ skills index  ]     fixed for the process
[ conversation  ]     grows, but only by appending
[ turn context  ]     reply_language, suggested_skill   <-- varies
[ user message  ]     <message>…</message>              <-- varies
```

This is why the per-turn context is wrapped in the **user** message rather than
injected into the system prompt, and why the system prompt is built by a
`systemMessageProvider` rather than an annotation with template variables. A test
runs two turns and fails if the two system prompts differ by a byte.

It also happens to serve a second purpose: the user's text arrives inside a
`<message>` element that the system prompt names as untrusted data, which gives
the model a structural cue that this region is content rather than instruction.

**The measurement to keep honest about:** cached-token counts are read from the
provider's own usage fields and exported as a metric. A cache-hit rate nobody
measures is a cache-hit rate nobody may claim.

## Lever 3 — the query never asks for what it will not use

Retrieval runs only when a tool is not going to answer instead. The router costs
nothing: it reads the skill hint the triage judge already produced.

The reason it is a router and not a threshold is a measurement, and it overturned
the first design — see [ADR 0010](adr/0010-rag-over-the-assistants-own-documentation.md).
Retrieved content is also **not** written into chat memory, so a chunk retrieved
on turn three is not still being paid for on turn twenty.

## Lever 4 — sub-agent context isolation

The trip-briefing workflow runs four sub-agents. Each calls its tools, reads the
raw JSON, and writes **one sentence** into the shared scope. What returns to the
conversation is a four-field briefing.

Doing the same work inline would put roughly 20 KB of API JSON into chat memory,
where it cannot be evicted without evicting the conversation around it.

The cost is stated rather than hidden: four model calls. That is why the workflow
is one tool the agent chooses, and why its own `SKILL.md` says a plain weather
question should go elsewhere.

## Lever 5 — compaction, with an invariant

At 14 400 estimated tokens — 60% of the self-imposed budget — the conversation is
compacted at the **end** of a turn, so no user waits for it.

**Rule 0: messages carrying the skill-activation attribute are never summarised
away.** Compacting one strips every tool from the agent, with no exception and no
log line; it simply starts answering from parametric memory. That is the first
test in the compactor's test class.

The pass tries to avoid the model entirely:

1. **Partition** — system message, activation-bearing messages, and the last six
   messages are kept verbatim.
2. **Cheap pass** — drop failed tool results (a failure carries no reusable fact),
   truncate survivors, de-duplicate identical ones. If this gets under the
   trigger, no model is called.
3. **Model pass** — summarise the rest with the *judge* model. Compression is not
   reasoning, and the judge measured 0.91 s median against 5.93 s.
4. **Reassemble** — the summary goes at position 1, the primacy slot immediately
   after the system message, which is where lost-in-the-middle says it is read
   best.

The summary carries facts with the tool that produced them, and constraints the
user stated. Its prompt forbids raw JSON, error text, URLs and verbatim phrasing:
a summary that reproduces the tone of a conversation has compressed nothing.

## Lever 6 — every result has a ceiling

| Thing | Ceiling |
|---|---|
| A tool response | per-endpoint byte budget, 32 KB default, truncation announced |
| A tool response's *shape* | projected to the fields the answer needs |
| Tool round trips per turn | 6 |
| Conversation | 40 messages, then compaction by token estimate |
| User message | 12 000 characters |
| Skills index | asserted ≤ 90 tokens per skill |

Field projection matters more than the byte cap. Cutting 284 KB of JSON at 32 KB
gives the model a fragment ending mid-object — invalid JSON it will still try to
interpret. Projection cuts on field boundaries, so the result is smaller *and*
well-formed.

## What was deliberately not built

Each of these is real, works, and is wrong at this size.

| Technique | Why not here |
|---|---|
| Programmatic tool calling ("code mode") | Reported 60–90% token savings, and needs a sandbox, an egress allow-list and an RCE threat model — for tools that are single GETs. The byte and projection budgets already bound the problem it solves. |
| LLMLingua-style prompt compression | 20× compression, needs a second model in-process. The prompt is ~1 500 tokens; compressing it costs a model and a class of silent semantic loss to save ~1 000. |
| Knowledge-graph triple extraction | The paper proposing it found the resulting reasoning chains "too fragile". |
| A dynamic supervisor planning loop | One model call per step, on top of a 0.9 s triage floor. A sequence and a parallel stage express this workflow without a planner. |
| Agent teams / peer-to-peer sub-agents | Higher token cost per teammate. A chat backend has one user and one turn. |
| `TokenWindowChatMemory` | A token window drops a different number of turns per conversation, so behaviour stops being reproducible between a test and production. Tokens are counted for the *compaction trigger* instead, where imprecision does not matter. |
| Explicit provider-side context caching | Real API, wrong size class. The stable prefix here is below the threshold where explicit caching pays for its bookkeeping. |
| Growing the system prompt to fix behaviour | The intuitive fix and usually the wrong one: a rule added to an over-long file is a rule that gets lost. |

The last row is the one worth internalising. Every measurement in this chapter
points the same way — **the fix for a model behaving badly is almost never one
more sentence in the prompt.**
