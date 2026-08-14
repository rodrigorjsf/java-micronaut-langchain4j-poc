# Context Stops Being Scarce

**Source:** https://paddo.dev/blog/million-token-context/
**Category:** Context Engineering

## Summary

Paddo.dev's analysis of Anthropic making 1M context windows generally available for Claude Opus 4.6 and Sonnet 4.6 at flat pricing. The article argues the real impact isn't expanded capability but the removal of the premium pricing tax on long context — which fundamentally changes workflows by delaying the lossy compaction events that degrade agentic coding sessions. Includes competitive landscape, benchmark data, and practical workflow implications.

## Content

Anthropic made the 1M context window generally available for Opus 4.6 and Sonnet 4.6 last week. The change is simple: flat pricing across the full window. A 900K-token request costs the same per-token rate as a 9K one. No beta header. No multiplier. No opt-in.

This matters less for what it enables and more for what it removes. **The tax on long context is gone.**

---

## What Actually Changed

Before this, Sonnet 4.6 had 1M context in public beta with a 2x input price multiplier above 200K tokens. Opus didn't have 1M at all. Now both models get the full window at standard rates:

- **Opus 4.6**: $5/M input, $25/M output — flat across 1M
- **Sonnet 4.6**: $3/M input, $15/M output — flat across 1M
- Media limits bumped 6x: up to 600 images or PDF pages per request
- Max, Team, and Enterprise Claude Code users get 1M by default

The API change is zero-effort. Drop the `anthropic-beta: long-context-2025-01-01` header. Everything else works the same.

---

## The Compaction Problem

This is where it gets practical. Claude Code compacts conversation history when context fills up — it summarizes earlier messages to make room. With 200K effective context, compaction kicked in regularly during long coding sessions. **Each compaction is a lossy compression.** The agent keeps the gist but loses specifics: exact error messages, architectural decisions made three hours ago, the nuance of why you chose approach A over approach B.

Jon Bell (Anthropic's CPO) reported a 15% decrease in compaction events with the 1M window. That number undersells the impact. Compaction doesn't degrade linearly — it compounds. One compaction is fine. Three in a session means the agent is working from a summary of a summary of a summary. **Pushing the first compaction further out doesn't just add 5x the context. It preserves the fidelity of everything that came before it.**

**Claude Code math:** Claude Code reserves ~33K tokens as buffer and triggers compaction at ~83.5% usage. At 200K, that's roughly 134K usable before compaction. At 1M, it's roughly 802K. Not 5x more context — 5x longer before the first lossy compression.

---

## Competitive Landscape

The context window race has been quieter than the capability race:

| Model | Window | Pricing |
|-------|--------|---------|
| Claude Opus/Sonnet 4.6 | 1M | **Flat pricing** |
| GPT-5.4 | 1M | Tiered (2x input above 272K) |
| GPT-4.1 | 1M | Flat pricing |
| Gemini 2.5 Pro | 1M | Tiered ($1.25 under 200K, $2.50 above) |

Everyone has 1M now. **The differentiator is pricing structure.** Claude is the only family where both the flagship and mid-tier model offer 1M at flat rates. OpenAI and Google both charge a premium for long context, which effectively discourages filling the window.

The benchmarks back the technical claim. Opus 4.6 scores 78.3% on MRCR v2 (Multi-Round Coreference Resolution) at 1M tokens. On the harder 8-needle variant, Opus hits 76% versus Sonnet 4.5's 18.5%.

---

## More Context, More Problems

Here's the tension: Anthropic gives you 1M tokens, but their own context engineering guidance says the goal is finding **"the smallest set of high-signal tokens that maximize the likelihood of your desired outcome."** More isn't better. Less, but right, is better.

The numbers tell the story. Opus 4.6's 78.3% MRCR score at 1M is the best in the industry. It also means **1 in 4 multi-needle retrievals fail** at that scale. At 256K, Opus scores 92-93%. The degradation curve is real, even for the best model.

### Lost in the Middle

The foundational research (Liu et al., 2024) documented a U-shaped performance curve: models attend well to the beginning and end of context, but information in the middle gets lost. Performance drops by more than 30% when relevant information shifts from the edges to the center. This is a structural problem rooted in how positional embeddings work, not a bug that gets patched away.

Anthropic knows this firsthand. They discovered that Claude 2.1's long-context accuracy jumped from 27% to 98% by adding a single prompt nudge: _"Here is the most relevant sentence in the context."_ The model had the information. It just wasn't attending to it.

### The Dumb Zone

The 12-factor agents framework puts it bluntly: fill your context window past 40% and you enter the "dumb zone." Signal-to-noise degrades, attention fragments, agents start making mistakes. This isn't a Claude-specific problem. It's architectural.

Princeton NLP's HELMET benchmark tested 59 models and found most degrade noticeably past 32K on summarization tasks. Open-source models collapse entirely.

**The paradox:** 1M context is most valuable when you don't use most of it. The win isn't cramming more in. It's having headroom so the important stuff stays in the high-attention zone longer, and compaction happens less.

### Cost Compounds Too

Flat per-token pricing doesn't mean cheap. A single 1M-token Opus request costs $5 input alone. A long coding session with multiple back-and-forths at high context adds up fast.

---

## What Changes in Practice

Context scarcity shaped how we work with AI coding agents. You learn to be concise. You front-load important context. You accept that the agent will forget things and you'll need to re-explain. These are workarounds for a constraint, not good practices.

With abundant context, the workflow shifts:

- **Longer uninterrupted sessions.** An agent that remembers your morning's architectural discussion when you ask it to implement something in the afternoon. No re-explaining.
- **Bigger codebases in context.** At 1M tokens, you could fit ~15,000 lines of code with room for conversation. The constraint shifts from "what fits" to "what's relevant."
- **Fewer manual interventions.** Each compaction event is a moment where the human might need to correct the agent's compressed understanding. Fewer compactions means longer stretches of productive autonomy.

> Context engineering > context stuffing

Anthropic's own guidance recommends subagent isolation, just-in-time context loading, and aggressive compaction over simply filling the window. The 1M ceiling is a safety net, not a target.

---

## The Trend Line

Context windows went from 4K (GPT-3.5) to 1M (multiple models) in three years. The growth has slowed — hardware constraints, diminishing returns on raw window size, and the shift toward better retrieval within existing windows. **The next unlock probably isn't 10M tokens. It's better attention over the tokens you already have.**

Anthropic's move to flat pricing signals they've solved the economics of long context, at least at current scale. When a capability stops costing extra, it stops being a feature and starts being assumed. That's when workflows actually change.
