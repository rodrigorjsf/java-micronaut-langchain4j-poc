# Building Your Own Agent Harness | Martin C. Richards

**Source:** https://www.martinrichards.me/post/building_your_own_agent_harness/
**Category:** Agentic Engineering

## Summary

Martin C. Richards argues that agent productivity depends less on which AI model you use and more on how you "harness" it — the set of skills, workflows, and methodology that teaches your agent how you think and how you build. His open-source project Atelier implements a research → plan → implement loop through structured skills, with explicit human annotation cycles before code is written. The harness concept, independently discovered by multiple practitioners, encodes discipline so you don't have to remember it each time.

## Content

I've been trying to write about coding agents for a while. Each time I sit down, the ground has shifted. The models change, my own workflow changes, and whatever I had to say feels stale before I finish saying it.

But one thing has stayed constant through all of it: the agents that produce good work are the ones that know how I work. My conventions, my patterns, my preferences. The ones that fail are the ones I drop into a codebase with no context and expect to read my mind. Complaining that an agent wrote bad code without giving it any of that context is like calling a new hire incompetent on their first day because they didn't already know your codebase.

## The Key Insight: Harness > Model

The models have also improved to a point where which model you use matters less than how you use it. The gap between Claude, Gemini, and GPT shrinks with every release. LangChain proved this on a benchmark: they improved their coding agent from 52.8% to 66.5% on Terminal Bench 2.0 by only changing the harness, keeping the model fixed. **Same model, better harness, better results.**

Mitchell Hashimoto gave this practice a name in February 2026: **harness engineering**. OpenAI and Martin Fowler picked it up.

Hashimoto's definition is reactive: anytime an agent makes a mistake, engineer a fix so it doesn't happen again. Richards thinks of it more broadly: **A harness is a set of skills, workflows, and methodology that teaches your agent how you think and how you build.** The models are unpredictable by nature. You can't change that. But you can teach them a methodology that makes success the more likely outcome rather than the lucky one.

## Everyone Landed Here Independently

Boris Tane nails the core principle:

> never let Claude write code until you've reviewed and approved a written plan.

He describes separating planning from execution as the single most important thing he does. The rest of his workflow follows:
- research the codebase first
- write a plan in a markdown file
- **annotate the plan back and forth until it's right**
- then implement.

His annotation cycle is the part worth paying attention to. He adds inline notes directly into the plan document, sends the agent back to update it, and repeats until you and the agent are aligned before a single line of code gets written. **The plan becomes shared mutable state between you and the agent.**

The same pattern showed up independently across the community:
- [HumanLayer](https://github.com/humanlayer/humanlayer) called it "RPI" (Research → Plan → Implement)
- Jesse Vincent's [Superpowers](https://github.com/obra/superpowers) landed on the same shape, now with 40k+ stars
- Atelier is Richards' version of the same idea

The convergence says more about the pattern than about any one implementation. **The harness doesn't need to be complicated. It needs to exist.**

## What a Harness is Made Of

In Atelier, the research → plan → implement loop runs through four skills:

**`spec:research`** explores the codebase, reads existing patterns, surfaces relevant context, and produces a `spec.md`, the research artifact that everything downstream depends on.

**`spec:plan`** takes that research and breaks it into tasks, producing a `plan.json`. This is the most important step. A good plan means the agent can commit to a specific approach and execute without constant course-correction. A bad plan means you spend tokens going back and forth mid-implementation, which is worse than spending that time upfront.

**`spec:implement`** executes the plan with TDD. Each task gets built, tested, verified.

**`spec:finish`** validates the whole thing and runs a review pass.

There's also `spec:orchestrator` handling skill routing, deciding which skill to load based on what you're doing.

```
graph LR
    R[spec:research] --> P[spec:plan]
    R -- "review & annotate" --> H[human]
    H -- "update spec" --> R
    P --> I[spec:implement]
    I --> F[spec:finish]
    I -- "gaps found" --> P
```

**This isn't waterfall.** Backflow is expected. You review the research, annotate it, and loop with the agent until the spec is right. Implementation can still find gaps in the plan and push back. When that happens you go back, update, and continue. That's what separates it from rigid spec-driven development that breaks the moment reality shows up.

## Different Kinds of Harness Components

Atelier has 34 skills organized into three namespaces:

**`spec:` skills** are sequential. They produce artifacts (documents, plans, code) and they're meant to be followed closely. You invoke them explicitly or a previous skill triggers them.

**`oracle:` skills** are advisory. `oracle:architect` applies DDD patterns and thinks about component responsibilities. `oracle:challenge` pushes back on your approach and pokes holes in your design. These adapt to context rather than following a rigid procedure.

**`code:` skills** are utilities: review, commit, the kind of thing you reach for when you need it.

> A spec workflow needs structure and artifacts, a thinking tool should adapt, and a commit helper just needs to work when called. Collapsing everything into one format loses that distinction.

Skills auto-load when relevant. Say "create a spec for user auth" and the agent matches that to `spec:research`. Language-specific skills (Drizzle, Fastify, FastAPI, SQLAlchemy, and others) activate based on what you're working in.

## How It Evolved

**August 2025 (v1):** Rigid. Subagents chained in sequence, waterfall-style. Context-reader feeds requirements-gatherer feeds spec-writer. Worked in a narrow sense but couldn't handle the back-and-forth that real development requires.

**January 2026:** Moved to a living `spec.md` model with delta tracking for brownfield changes (`ADDED`, `MODIFIED`, `REMOVED` markers). Closer, but still too ceremonial.

**Current version:** Dropped the ceremony. Agent skills are the reason. Before skills, you had agents, commands, and reference docs, and you tried to chain them together yourself. Skills changed that — they let you capture knowledge in a form the agent can find on its own. You describe what you want to do, and the agent loads the relevant skill by context.

## Build Your Own Harness

Install Atelier if you want: `npx skills add martinffx/atelier`. Or grab specific skills: `npx skills add martinffx/atelier --skill spec:research`.

But the real suggestion is to **build your own**. My TypeScript opinions aren't yours. My architecture preferences won't match your codebase. The value of Atelier isn't in the specific skills. It's in the idea that your agent's harness should be engineered with the same care as the software it produces.

Start with the research → plan → implement loop. Get that working in your own way. Add skills for how you write code, how you test, how you think about design. Boris Tane's annotation cycle is meticulous, Superpowers is a full framework, and Atelier sits somewhere in between. They all converge on the same observation: **the discipline matters more than the tools. The harness just encodes that discipline so you don't have to remember it each time.**

## Further Reading

- [My AI Adoption Journey](https://mitchellh.com/writing/my-ai-adoption-journey) by Mitchell Hashimoto
- [Harness Engineering](https://openai.com/index/harness-engineering/) by OpenAI
- [Harness Engineering](https://martinfowler.com/articles/exploring-gen-ai/harness-engineering.html) by Martin Fowler / Thoughtworks
- [Improving Deep Agents with Harness Engineering](https://blog.langchain.com/improving-deep-agents-with-harness-engineering/) by LangChain
- [How I Use Claude Code](https://boristane.com/blog/how-i-use-claude-code/) by Boris Tane
- [Superpowers](https://github.com/obra/superpowers) by Jesse Vincent
- [HumanLayer RPI](https://github.com/humanlayer/humanlayer)
- [Atelier](https://github.com/martinffx/atelier)
