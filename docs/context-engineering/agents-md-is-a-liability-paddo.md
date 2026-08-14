# Your AGENTS.md is a Liability

**Source:** https://paddo.dev/blog/your-agents-md-is-a-liability/
**Category:** Context Engineering

## Summary

Paddo.dev's research-backed argument that growing AGENTS.md files beyond a certain size actively harms AI agent performance. Drawing on the IFScale benchmark (NeurIPS 2025), "lost in the middle" research, Chroma's context rot findings, and Google's prompt repetition study, the article demonstrates that attention dilution, primacy bias, and the dumb zone make long instruction files counterproductive. The fix: shorter, modular, front- and back-loaded instruction files.

## Content

Your AGENTS.md is getting longer. Every time an agent makes a mistake, you add a rule. Every edge case gets a line. Every convention gets documented. You're at 300 lines, then 500, then 1000.

**You're making it worse.**

---

## The 500 Instruction Ceiling

In July 2025, Distyl AI published "How Many Instructions Can LLMs Follow at Once?" — a NeurIPS 2025 workshop paper that tested 20 frontier models against their IFScale benchmark. The task: generate a business report while including specific keywords. Each keyword counts as one instruction. Scale from 10 to 500.

**The best frontier model scored 68% at 500 instructions. One in three instructions simply got skipped.**

Three distinct degradation patterns emerged (tested on mid-2025 models):

- **Threshold decay**: Near-perfect performance until a tipping point, then sharp decline. Reasoning models like o3 and Gemini 2.5 Pro held steady through 100-250 instructions before falling off a cliff. Current reasoning models (GPT-5.3, Gemini 3.1 Pro, Opus 4.6) likely push the threshold higher, but the cliff still exists.
- **Linear decay**: Steady, predictable decline from the start. GPT-4.1, Claude Sonnet 4.
- **Exponential decay**: Rapid collapse. GPT-4o, LLaMA-4-Scout.

Every model showed **primacy bias**: earlier instructions get more attention than later ones. And as instruction density increases, errors shift from _modification_ (doing it wrong) to _omission_ (not doing it at all). **The model doesn't misinterpret your 400th rule. It doesn't see it.**

---

## Why It Happens: Five Compounding Problems

The 500-instruction ceiling isn't a single bug. It's at least five overlapping architectural constraints.

### 1. Lost in the Middle

Stanford and Meta's 2023 paper (TACL 2024) documented the U-shaped attention curve. Models attend strongly to the beginning and end of their context, but the middle is a dead zone. With 20 documents in context, placing the answer in the middle dropped accuracy by 20+ points. In one case, GPT-3.5-Turbo performed _worse with context than without it_. Adding information actively hurt the model.

This has improved. Claude Opus 4.6 shows less than 5% degradation across its full window in retrieval benchmarks. But retrieval and instruction-following are different tasks. Finding a needle is easier than simultaneously obeying 500 constraints.

### 2. The Dumb Zone

Dex Horthy (HumanLayer) coined this at the AI Engineer Code Summit. Past roughly 40% of context capacity, "the model starts drifting, hallucinating, and forgetting its own instructions." Matt Pocock corroborated the threshold.

This isn't a small-model problem. It's how attention works. Token relationships scale quadratically. Each additional token makes every other token slightly harder to attend to.

> The more you use the context window, the worse the outcomes you'll get.
> — Dex Horthy, HumanLayer

### 3. Context Rot

Chroma's research identified three compounding mechanisms: the lost-in-the-middle positional bias, attention dilution (softmax spreads attention thinner as context grows), and distractor interference (semantically similar but irrelevant content causes hallucinations). **The critical finding: degradation happens at every context length increment, not just near the limit.** A 1M token window still rots at 50K tokens.

### 4. Attention Sinks

Xiao et al. (ICLR 2024) showed that initial tokens receive disproportionate attention regardless of their semantic content. Replace your first tokens with newline characters and the effect persists. It's positional, not semantic. **This is why instructions at the top of your AGENTS.md get followed more reliably: they're sitting on the attention sink.**

### 5. Attention Dilution

The transformer's softmax attention is a zero-sum game. Attention weights are positive and sum to 1. More tokens means less attention per token. This is structural: **you can't add context without diluting attention to existing context.** Every line in your AGENTS.md competes with every other line for the model's focus.

---

## The Repetition Hack

Here's a counterintuitive finding. Google Research published "Prompt Repetition Improves Non-Reasoning LLMs" in December 2025. The method: send `<QUERY><QUERY>` instead of `<QUERY>`. Just repeat the prompt.

Results: **47 wins out of 70 benchmark-model combinations, 0 losses.** Gemini 2.0 Flash-Lite jumped from 21% to 97% on middle-of-list retrieval. The x3 variant (three repetitions) often outperformed x2 on positional tasks.

The mechanical explanation is elegant. Autoregressive models process tokens left to right. Causal masking means each token can only attend to preceding tokens. When the model first reads your instructions, it's blind to the context that follows. By repeating the instructions after the context, the second copy can attend to everything — the instructions _and_ the context they apply to.

**The authors frame it directly: prompt repetition simulates bidirectional attention within a unidirectional architecture.** The model gets hindsight.

This is already happening in production. Claude Code injects `<system-reminder>` tags throughout tool results. Claude.ai uses `<long_conversation_reminder>` tags in extended conversations. OpenAI's docs explicitly recommend repeating instructions before and after primary content. The "sandwich" prompt pattern predates the paper.

The downside: repetition eats context, which triggers the very problems you're trying to solve. It's a workaround for a structural limitation, not a fix.

---

## What This Means for Your AGENTS.md

The research converges on a few actionable principles:

**Shorter is better.** Every additional instruction dilutes attention to all others. A 200-line AGENTS.md with 5 critical rules is worse than a 50-line file with the same 5 rules and less noise.

**Front-load and back-load.** Primacy bias and recency bias are both real. Your most important rules belong at the very beginning and the very end. The middle is where rules go to die.

**Modularize.** Claude Code's Skills system, path-specific rules, and lazy-loaded context exist for a reason. Don't stuff everything into one file. Load domain-specific rules only when the domain is active.

**Prune aggressively.** Semantically related but irrelevant instructions are worse than unrelated ones (Chroma's distractor interference finding). That section about Docker conventions doesn't help when the agent is writing React components. It actively hurts.

**Negative rules are fragile.** "Do NOT do X" fails more often than "always do Y." Prefer positive instructions where possible.

**The sweet spot is in the low hundreds.** Reasoning models maintain near-perfect performance through 100-250 instructions before degrading. For non-reasoning models, degradation starts earlier.

> Agent-generated context quickly turns into noise rather than useful information.
> — JetBrains Research, NeurIPS 2025

---

## Why Architectural Solutions Matter More Than Prompt Engineering

Every mitigation for context degradation — repetition, reminders, sub-agents, summarization — consumes more context, which makes the problem worse. **You're fighting attention dilution by adding tokens that dilute attention.**

This is why architectural solutions matter more than prompt engineering. Dex Horthy's RPI loop (Research → Plan → Implement with fresh context per phase) works because it doesn't try to fit everything into one window. JetBrains' "Complexity Trap" research showed that simple observation masking — hiding tool output details — cuts costs 50% without degrading task performance.

The thread connecting all of this: autoregressive causal masking is a fundamentally lossy way to process instructions. Everything we do in prompt engineering is a workaround for that architectural constraint. The model reads left to right, attends preferentially to the edges, and loses the middle. More context makes each piece of context weaker.

**Your AGENTS.md isn't a knowledge base. It's an attention budget. Spend it wisely.**

---

## Further Reading

- [IFScale: How Many Instructions Can LLMs Follow at Once?](https://arxiv.org/abs/2507.11538) (Distyl AI, NeurIPS 2025)
- [Lost in the Middle](https://arxiv.org/abs/2307.03172) (Liu et al., TACL 2024)
- [Context Rot](https://research.trychroma.com/context-rot) (Chroma Research)
- [Prompt Repetition Improves Non-Reasoning LLMs](https://arxiv.org/abs/2512.14982) (Google Research)
- [Efficient Streaming Language Models with Attention Sinks](https://arxiv.org/abs/2309.17453) (Xiao et al., ICLR 2024)
- [The Complexity Trap](https://blog.jetbrains.com/research/2025/12/efficient-context-management/) (JetBrains Research, NeurIPS 2025)
- [No Vibes Allowed: Dex Horthy on the Dumb Zone](https://bagrounds.org/videos/no-vibes-allowed-solving-hard-problems-in-complex-codebases-dex-horthy-humanlayer)
