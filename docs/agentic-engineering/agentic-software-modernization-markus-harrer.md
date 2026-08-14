# Agentic Software Modernization: Chances and Traps | Markus Harrer

**Source:** https://markusharrer.de/blog/2026/02/17/agentic-software-modernization-chances-and-traps/
**Category:** Agentic Engineering

## Summary

Markus Harrer argues that agentic AI can genuinely modernize legacy software (COBOL, RPG, etc.) but requires disciplined preparation — not "vibe coding." The key insight is that the LLM context window creates a "Dumb Zone" above ~40% utilization, and legacy codebases are too large to fit in context. The solution is the RPI workflow (Research, Plan, Implement) with active context compaction, iterative refinement with critic agents, codebase hygiene investment, and maintaining traceability links from new code back to the original business logic.

## Content

Modernizing legacy software (often massive, undocumented "brownfield" projects in languages like COBOL or even older RPG) is one of the toughest disciplines in software engineering. The promise of "AI agents" is tantalizing: Can autonomous AI agents automate this exhausting modernization process?

The answer from experts conducting recent Stanford studies and leading AI engineering firms (such as OpenHands and HumanLayer) is a big **YES**, but not the way most people think about it.

Simply unleashing AI agents on an old codebase and hoping for a miracle is a recipe for disaster. Successful Agentic Software Modernization requires a fundamental shift in modernization workflows: **away from vibe coding towards disciplined preparation and execution.**

## The Core Problem: The Context Bottleneck

Before diving into the topic, we must understand the central constraint. AI models (LLMs) are "stateless." They only know what exists in their current context window.

In complex legacy systems, it is impossible to cram the entire context (millions of lines of code, dependencies, business logic) into this window. When the window becomes too full (according to Dex Horthy of HumanLayer, often above ~40% utilization), the model enters the **'Dumb Zone'** where response quality degrades rapidly and hallucinations increase.

While approaches like RAG and agentic search (smart use of glob and grep) help with larger datasets, they face a more fundamental problem identified by recent Stanford studies: **Entropy**. When AI agents work within existing low-quality codebases, the produced code mirrors the low standards of the existing environment (leading to a death spiral). More and more harmful code is produced in a short amount of time, effectively automating the creation of technical debt.

The art of Agentic Software Modernization must not be about generating more legacy code faster, but about **surgically managing the AI agent's access to the right context** to understand and improve the system.

## Emerging Practices for Agentic Modernization Workflows

### 1. Implement a RPI Workflow (Research, Plan, Implement)

The biggest trap is letting the agent code immediately. Instead, divide the process into strict phases:

**Phase 1: Research (Understanding)**
The agent analyzes _only_ the existing codebase to understand how a feature works. The output is not code, but a summary (e.g., a Markdown document) explaining where the relevant logic resides. Using data-driven approaches like Software Analytics can help analyze software systems on a large scale. You can enrich the codebase or summary beforehand to guide the agent through it (e.g., signaling outdated parts, no-go areas, and code that fits current system ideas).

**Phase 2: Plan (Intent Compression)**
Based on the research, the agent creates a detailed plan of which files need to be changed and how. This plan represents the "compressed intent" of the modification. Scope down those activities. In the best case you can switch from agentic workloads towards **rule-based search and replace workloads**, letting an agent craft change recipes and execute those deterministically instead of in a non-deterministic way.

**Phase 3: Implement (Coding)**
Only now does the agent change or write code, based strictly on the approved plan or on your deterministic transformation rules.

**Why?** If the plan is non-existent, wrong, or too vague, 1,000 lines of generated code are worthless and changed code is tedious to review because of plenty of sloppy code. Invest human intelligence in reviewing the research and planning steps to create AI and human alignment early on and not just at the end with reviewing the final code.

### 2. Use Iterative Refinement Techniques for Continuous Feedback

Never attempt a complex migration (e.g., a COBOL file to Java) in a single "One-Shot" prompt. The team at OpenHands demonstrated that this almost always leads to hallucinations.

Instead, use an iterative loop with specialized roles:
- **Engineer Agent:** Attempts to solve the task (e.g., migrating code).
- **Critic Agent:** A separate agent that _only_ reads. It analyzes the generated code, runs tests, and provides harsh feedback (scores).

The process runs in loops: Engineer delivers → Critic evaluates and sends feedback → Engineer improves → Repeat until quality standard is met.

Leverage mechanisms like compilation errors, code duplication detection, and architectural violations as key levers to guide the agent mechanically.

### 3. Invest in "Codebase Hygiene" First

AI is not a magic wand that turns bad code into good code. The Stanford study by Yegor Denisov-Blanch shows a clear correlation:
- In **clean environments** (high test coverage, good modularity, typing), AI can autonomously drive a large share of sprint tasks.
- In **"dirty" environments** (high entropy, technical debt), the AI struggles, produces more errors, and can actually accelerate technical debt (the "Rework" trap).

Before scaling AI, you must clean up the foundation. Renaming cryptic one-letter variables to reflect the actual technical or business domain is a high-leverage move. Building out higher level concepts or refactoring towards well-known patterns or idioms is also valuable. Most of those activities are safe refactorings that usually don't break the code.

### 4. Practice Active Context Compaction

When an agent strays off the path, the human impulse is often to correct it within the same chat. **This is a mistake.** Every failed attempt clutters the context window with "noise."

A better approach — active **context compaction** (context reset and starting over):

1. Have the agent summarize the current state and findings into a compact file (`state.md` or similar).
2. Start a completely new chat with a fresh context.
3. Feed only the summary in as the starting point.

This keeps the agent in the "Smart Zone" of its context window.

For side projects using models with limited context windows (DeepSeek, Minimax, Moonshot), active context compaction and rigid external management of the current state and next steps is essential for good results.

### 5. Maintain "Traceability Links"

When migrating legacy code (e.g., COBOL to Java), the connection to the original business logic must never be lost. OpenHands recommends that the agent insert comments in the new code that link exactly to the line numbers of the old code where that logic originated. This is essential for future debugging and audits.

When using graph analytics on the whole codebase or creating flowcharts, ensure that line numbers or identifiers or file names are used in generated outputs so you can quickly verify they are not hallucinated.

## Traps to Avoid

### 1. Falling into the "Vibe Coding" Trap

"Vibe Coding" describes the back-and-forth chatting with a model, guided more by feelings than by specifications ("Make that prettier," "No, that feels wrong"). This leads to bloated context windows and confused models. AI Engineering in legacy system environments requires precision, not "vibes."

### 2. Underestimating "Rework"

Stanford studies clearly show that while AI tools increase output (more Pull Requests), they often dramatically increase rework: the time developers spend repairing or rewriting AI-generated code. If you only look at speed/volume, you miss the massive cost of quality assurance.

### 3. Rely Blindly on Line-by-Line Code Reviews

In a world where an agent can generate 20,000 lines of TypeScript code in minutes, traditional human line-by-line review is no longer scalable.

The **Hierarchy of Leverage** from Dex Horthy states: **1 Bad Line of Plan == 100 Bad Lines of Code.** Shift the focus of human review "left", to the research results and the plan, before the code is even written.

In the research stage, look at how to systematically get to the refactoring spots. If you can come up with rule-based changes, most of them are structurally equal over the completed codebase. You don't have to review line-by-line, but change pattern by change pattern — which is very efficient.

### 4. Expect Magic in Niche Languages

AI model performance depends heavily on training data. For popular languages (Python, Java, JS), they work excellently. For niche languages or very old dialects (specific COBOL variants, obscure DSLs, RPG), using AI can actually **decrease productivity** according to Stanford data, because the agent hallucinates and the human spends all their time correcting it.

Check the corresponding tags on StackOverflow and the TIOBE programming popularity index. Every language that was not under the top 10 in the last few years needs a different approach. Maybe a broader reverse engineering towards specs or tests is needed, or use a more traditional transpiler approach to convert the niche language to a more popular programming language level that an AI agent can then work with.

## Conclusion: From Coder to Architect of Intent

Agentic Software Modernization works, but it requires discipline. The role of the human developer is shifting. We are becoming less writers of syntax and more the **architects of the intent**.

Think of AI agents in legacy systems as new senior developers that can do really amazing things but need decent onboarding: step-by-step introduction to the system, letting them know the background of the existing code, and carefully introducing them to the nasty parts of the systems over time.

Those who just click "Refactor all this" will end up in chaos.

## Sources & Further Reading

- **[Calvin Smith / OpenHands: Refactoring COBOL to Java with Agentic AI with an Iterative Refinement Workflow](https://www.youtube.com/watch?v=4LUtguF160A)**
  - Topics: Iterative Refinement, Critic Agents, Traceability Links
- **[Dex Horthy (HumanLayer): Context Engineering SF: Advanced Context Engineering for Agents](https://www.youtube.com/watch?v=VvkhYWFWaKI)**
  - Topics: Hierarchy of Leverage, 1 Bad Line of Plan vs Code
- **[Dex Horthy (HumanLayer): No Vibes Allowed: Solving Hard Problems in Complex Codebases](https://www.youtube.com/watch?v=rmvDxxNubIg)**
  - Topics: RPI Workflow (Research, Plan, Implement), Context Compaction
- **[Yegor Denisov-Blanch (Stanford): Can you prove AI ROI in Software Eng? (Stanford 120k Devs Study)](https://www.youtube.com/watch?v=JvosMkuNxF8)**
  - Topics: Measuring ROI, The danger of "Rework", Entropy in codebases
- **[Yegor Denisov-Blanch (Stanford): Does AI Actually Boost Developer Productivity? (100k Devs Study)](https://www.youtube.com/watch?v=tbDDYKRFjhk)**
  - Topics: Productivity stats, Niche vs. Popular languages, Codebase Hygiene
- **[Awesome Agentic Software Modernization](https://github.com/feststelltaste/awesome-agentic-software-modernization)** - Curated resource list by Markus Harrer
