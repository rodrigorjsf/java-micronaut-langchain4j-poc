---
name: llm-cost-observability
description: Make LLM spend attributable and cache hits provable. Use when the model bill is one number nobody can decompose, when claiming a prompt-caching win, when pricing lives in code, or when instrumenting an agent with more than one model call per request. For why a cache is not hitting rather than how to prove it does, use agentic-service-composition.
---

# LLM cost observability

An agentic request makes several model calls of very different value: a cheap
classifier, an expensive agent, a summariser, a guardrail. Measured as one total,
none of the decisions that produced them can be defended.

## Tag every metric with a role

Address models by **role** — `judge`, `agent`, `summarizer` — never by model id,
and tag every token, cost, latency and error metric with it.

This is the point of having roles at all. Without the tag, "the LLM bill" is one
number and the claim your architecture rests on — *the classifier is most of the
calls and a small part of the cost* — is unprovable. With it, that claim is a
dashboard.

It also makes model choice a deployment decision: no class names a model, so
swapping the classifier is a config change and its before/after is visible in the
same graph.

Emit, per role and model:

```
tokens{role,model,kind=input|output|cache_read|cache_write}
cost_usd{role,model}
latency{role,model}
errors{role,model,exception}
unpriced_calls{role,model}
```

## Measure cached tokens, or stop claiming the cache

Providers report cache hits in provider-specific fields — a `cached_tokens` under
input-token details on one, a `cachedContentTokenCount` on another. The vendor-
neutral usage type in your framework will not have them.

If you laid out the prompt to earn cache hits — stable prefix first, per-turn
data last — then **a cache-hit rate nobody measures is a cache-hit rate nobody
may claim.** Read the provider-specific type and count it.

On a span, use the names the OpenTelemetry GenAI semantic conventions give
them, so any backend that reads the conventions can sum them:

| Attribute | What it counts |
|---|---|
| `gen_ai.provider.name` | the provider that served the call — **Required** on inference spans; it replaced `gen_ai.system`, which the registry marks deprecated |
| `gen_ai.usage.input_tokens` | **every** input token, cache reads and cache writes included |
| `gen_ai.usage.cache_read.input_tokens` | input tokens served from a provider-managed cache |
| `gen_ai.usage.cache_write.input_tokens` | input tokens written to a provider-managed cache |

The two cache counts are **subsets** of `input_tokens`, not additions to it: the
conventions say each "SHOULD be included in `gen_ai.usage.input_tokens`". Sum all
three and every cached token is counted twice.

These attributes are at **Development** status — they can still be renamed, as
`gen_ai.system` was. Pin the conventions version you followed and re-read the page
when you upgrade. [sourced — OpenTelemetry, *Semantic conventions for generative
client AI spans*, status Development,
https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-spans.md,
and the deprecation of `gen_ai.system` in
https://github.com/open-telemetry/semantic-conventions/blob/main/docs/registry/attributes/gen-ai.md,
both read 2026-09-27]

## Bill cache reads and cache writes at their own rates

A cache is not only a discount. **Writing to it can cost more than not caching.**
On Anthropic a 5-minute cache write is 1.25× the base input price and a 1-hour
write is 2×, while a cache read is 0.1× [sourced — Anthropic, *Prompt caching*,
pricing section, https://docs.claude.com/en/docs/build-with-claude/prompt-caching,
read 2026-09-27]. Gemini's explicit context caching charges a reduced rate per
cached token read **plus storage per million tokens per hour** [sourced — Google,
*Gemini Developer API pricing*, https://ai.google.dev/gemini-api/docs/pricing,
read 2026-09-27].

So price input in three parts, each at its configured rate:

```
fresh       = max(0, input - cache_read - cache_write)
cost_input  = fresh × input_rate
            + cache_read  × cache_read_rate
            + cache_write × cache_write_rate
```

Clamp `fresh`: a provider over-reporting cached tokens should not produce a
negative charge. A write rate left unconfigured is the same silent hole as an
unpriced model — count it the same way. And a cache that is written on every call
and read on none (a timestamp at the top of the prompt does it) is **more expensive
than no cache**; only the read/write split shows it.

## Prices belong in configuration

Prices change without asking. A stale constant produces a cost report that is
**confidently wrong**, which is worse than no report.

Put them in config, keyed by model id, with the source URL and the date read in a
comment. Record paid-tier rates even while running on a free tier, so the
accounting is honest the moment billing is enabled.

A model with **no** configured price is billed at zero **and counted in a
separate metric**. Silence about an unpriced model is how a total quietly
excludes half the spend.

## Use decimal arithmetic

Per-token prices are around 1e-7 USD. Summing millions of those in binary
floating point drifts in a way that stays invisible until the invoice disagrees
with the dashboard. Use the platform's decimal type.

## The listener must never throw

Most frameworks **swallow** exceptions thrown by model listeners. A failure there
is not a loud error; it is a silent hole in the accounting.

Catch inside the listener and log the reason. Otherwise the first
`NullPointerException` on an unusual response quietly stops counting, and the
graph just looks like traffic dropped.

## Make the test double faithful about listeners

If you test the pipeline with a scripted model, have it notify listeners around
every call the way a real provider client does.

Without that, the double is faithful about requests and silently unfaithful about
accounting — and the cost metrics are exercised by nothing at all.

## One surface, not two

Resist keeping a running total in memory *and* a counter in the metrics registry.
Two sources of truth for the same number is how they diverge. Pick the metrics
registry; it already aggregates, exports and survives a restart boundary
correctly.
