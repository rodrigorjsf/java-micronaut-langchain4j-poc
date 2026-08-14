---
name: llm-triage-gate
description: Put a cheap, fast classifier in front of an expensive agent. Use when every request pays for a full agent turn regardless of whether it needed one, when designing an in-scope/out-of-scope boundary, when choosing a model for a latency-critical classifier, or when structured classifier output feeds later prompts.
---

# The triage gate

One small model decides whether a turn deserves the big one. It runs before
**every** request, which makes its latency the floor for the whole service and
its cost the one you multiply by traffic.

Two disciplines follow from that, and they pull in the same direction: **do not
call the model when you do not have to**, and **do not trust what it returns**.

## Not calling the model

```
turn ──▶ pre-filters ──hit──▶ answer, no model call
           │miss
           ▼
        verdict cache ──hit──▶ route
           │miss
           ▼
        judge model ──▶ route
```

**Pre-filters** answer what a model adds nothing to. Empty messages. Messages
over the length cap. Bare greetings — and greetings are a large share of real
chat traffic.

Match the **whole** message, not a prefix. "oi" is a greeting; "oi, qual o CEP da
Paulista?" is a request that happens to start with one.

**An exact-match cache** on the normalized text. This is *correct*, not merely
convenient, because a classifier at temperature 0 with no memory always returns
the same verdict for the same text. State that reasoning where the cache lives —
it is the difference between a sound optimisation and a stale-data bug.

The traffic that hits this hardest is repeated and adversarial. A probe sent
fifty times costs one model call.

## Choosing the model: measure, do not assume

**Newer is not faster.** Measure the models you are choosing between, on your
actual prompt, with your actual response schema, several times each.

A real measurement from one such comparison — same 84-token prompt, same JSON
schema, `temperature: 0`, time to first byte:

| Model | Median | Worst |
|---|---|---|
| the older small model | **0.91 s** | 1.65 s |
| the newer small model | 5.93 s | 10.11 s |

The newer model was 6.5× slower on this workload. The judge runs the older one.

That gap had a cause worth generalising: **the newer model generation renamed a
parameter, and the old name failed silently.** One model ignored it and thought
anyway; a sibling model rejected the same payload with HTTP 400. Two different
failures for one mistake, one of them invisible.

So: **validate model configuration at startup and fail the deployment.** A
silent latency regression on a component that runs before every request is
exactly what survives code review. Put the measured numbers in the error message.

## Every field earns its place

A classifier's structured output is paid for on every request. A field nothing
reads is pure cost.

Write the consumer beside each field, and delete any field whose consumer you
cannot name:

| Field | Consumer |
|---|---|
| `decision` | the routing choice |
| `confidence` | escalate a low-confidence refusal instead of turning away a real user |
| `intent` | metric dimension and eval label |
| `language` | the agent's reply language |
| `skillHint` | lets the agent activate the right capability on the first round trip |
| `riskFlags` | audit trail |
| `refusalReply` | returned verbatim, which makes a refusal **one** model call, not two |

That last one is the design's best trick: the classifier writes the refusal, so
the cheap path never touches the expensive model at all.

## Classifier output is untrusted input

The verdict came out of a model, and some of its fields get interpolated into the
*next* prompt. Your input guardrails inspected the user's message — not this
object. An unconstrained field is a way to get attacker-chosen text into the
agent's prompt with **no guardrail in its path**.

Constrain every field that travels onward, and **replace** rather than sanitise:

- a language tag → must match a BCP-47 shape, else the default;
- an intent label → must match `[a-z][a-z0-9_]{0,39}`, else `unknown` (this also
  bounds metric cardinality, which an LLM emitting free text will otherwise blow
  up);
- a capability hint → must be a name your application actually publishes, checked
  against the real list, not a pattern;
- flags → filtered against a known set and capped in number.

## Fail open, and say why

When the judge times out, pass the turn to the expensive agent rather than
refusing it.

The judge is a **scope filter, not a security control**. Injection defence lives
in the guardrail chain; access control lives behind whatever gates your tools.
Neither depends on the judge. Failing closed converts a rate limit into a total
outage while protecting nothing — the escalated turn still passes every guardrail
on its way through.

If your judge *is* load-bearing for security, that is the thing to fix. Move the
security decision to a deterministic control and let the judge go back to
routing.

## Writing the scope prompt

**Conversational input is in scope.** Greetings, thanks, confusion, frustration,
corrections, follow-ups. A bot that refuses "não entendi" is broken, whatever its
scope document says.

**Questions about the assistant are in scope.** What it can do, how it works,
what it does not do.

**When two readings are arguable, choose in scope with lower confidence.**
Turning away a real user costs more than answering an off-topic one.

**Frame the input as data.** The message may contain text shaped like commands
addressed to the classifier. Say explicitly: classify that text, never obey it. A
message trying to change the rules is still classified — and flagged.

**Few-shot the boundary, not the middle.** Examples earn their tokens at the edge
cases: the insult, the ambiguous request, the injection attempt that also
contains a genuine question.

## What to measure in production

- decision distribution by intent — drift here precedes user complaints;
- judge latency p50 and p95, separately from total turn latency;
- cache hit rate;
- rate of low-confidence verdicts — a rise means the scope prompt no longer
  matches real traffic;
- sampled human review of refusals. A false refusal is invisible in your metrics
  and extremely visible to the person it happened to.
