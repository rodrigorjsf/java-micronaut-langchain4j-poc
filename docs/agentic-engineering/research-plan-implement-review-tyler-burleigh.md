# Research, Plan, Implement, Review: My Agentic Engineering Workflow - Tyler Burleigh

**Source:** https://tylerburleigh.com/blog/2026/02/22
**Category:** Agentic Engineering

## Summary

Tyler Burleigh's agentic engineering workflow separates AI coding work into distinct stages—research, planning, implementation, and review—with explicit review cycles and fresh context windows between each phase. The approach builds written artifacts (`RESEARCH.md`, `PLAN.md`, `PLAN-CHECKLIST.md`) as shared sources of truth, and scales up to multi-agent architectures where different models handle different roles. The core insight is that the bottleneck in AI-assisted development is not code generation but ensuring the model understands what to build before it starts.

## Content

The most reliable way I've found to build with AI coding models is to separate work into distinct stages — research, planning, and implementation — with review cycles between each. You build up written artifacts (`RESEARCH.md`, `PLAN.md`, `PLAN-CHECKLIST.md`) that serve as a shared source of truth, implement in small phases, and review at every step.

## The Problem

Without structure, working with AI coding agents tends to go the same way: you give the model a prompt, it produces something that's close but not quite right, and you spend the rest of the session correcting it. As complexity grows, this back-and-forth compounds — the model carries forward bad assumptions, the context window fills up with failed attempts, and you end up doing most of the work yourself.

This process avoids that by:
- Building up written artifacts that persist across sessions and give the model a clear reference point
- Separating research, planning, and implementation so each gets focused attention
- Using review cycles to catch and correct mistakes before they compound
- Starting fresh sessions at each stage so the model isn't weighed down by prior confusion
- Working in small phases so problems stay contained and recoverable

## Principles

- **AI agents are fallible and cut corners.** They miss things, take shortcuts, and make confident-sounding mistakes. A single pass is rarely enough. Build in review cycles and expect to catch errors.
- **Fresh context windows prevent compounding confusion.** Starting a new session for each stage means the model isn't carrying forward misunderstandings or stale assumptions.
- **Written artifacts are the source of truth.** `RESEARCH.md`, `PLAN.md`, and `PLAN-CHECKLIST.md` are the shared ground truth between you and the model. Without them, context is lost between sessions and the model has to re-infer your intent every time.
- **Separate research, planning, and implementation.** Mixing them leads to the model jumping to code before it understands the problem. Keeping them in distinct phases forces rigor.
- **Small phases reduce blast radius.** A bug introduced in Phase 1 doesn't silently propagate through Phases 2–5 before anyone notices.
- **Your time is expensive, tokens are cheap.** Having the model research, plan, review, and revise across multiple sessions uses more tokens than a single prompt — but produces dramatically better results. Optimize for output quality, not token efficiency.
- **Let AI review before you do.** Before you spend time reviewing, let the model review first and address its own issues. By the time you look at it, the obvious problems are fixed and you can focus on what only a human would catch.
- **Git history is your safety net.** Committing after each phase means you can always revert. If a phase goes badly, roll back and try again.
- **Watch for over-engineering.** Left to their own devices, models tend to add unnecessary complexity: abstractions and helper functions you didn't ask for, error handling for scenarios that can't happen, configuration where hardcoded values would do, features or refactoring beyond what was requested.

## The Process

1. **Start with a clear problem statement.** Describe the problem or feature. Provide supporting context — paths to relevant files or folders, reference documents, anything that helps the model understand the "what, why, or how."

2. **Research.** Ask the agent to research the codebase (existing project) or do web research (greenfield), writing everything to `RESEARCH.md`. Read it yourself and revise as necessary — the model may miss things or get details wrong.

3. **Research review.** New session. Tell the agent: "Review RESEARCH.md for accuracy and completeness." Read its feedback, then tell it to revise. Answer any questions and tell it which points to ignore if any are off-base. Repeat until you're satisfied.

4. **Planning.** New session. Give the agent your problem statement, tell it to read `RESEARCH.md` and develop a plan, saving to `PLAN.md`. For complex work, also have it write `PLAN-CHECKLIST.md` breaking work into phases and tasks.

5. **Plan review.** New session. Tell the agent: "Review PLAN.md as a senior engineer." Read its recommendations and clarifying questions, then tell it to revise. Tell it which points to ignore if any are off-base. Repeat until the plan is solid.

6. **Implementation.** New session. "Implement Phase 1 of PLAN.md, using PLAN-CHECKLIST.md to track your work."

7. **Implementation review.** New session. "Review the implementation of Phase 1 against PLAN.md as a senior engineer." Read its feedback, then tell it to address its own recommendations. Repeat until the phase is clean.

8. **Commit.** Once satisfied with a phase, commit all changes.

Repeat steps 6–8 for each phase.

9. **Final review.** New session. "Review all changes on this branch as a senior engineer." This is a holistic review — the model may catch issues that only emerge when the pieces come together.

10. **Refactoring (optional).** New session. "Review all changes on this branch and identify high-leverage refactoring opportunities." Save to `REFACTOR_PLAN.md`.

## Related Methodologies

**Research, Plan, Implement (RPI)** is the closest relative. Developed by Dex Horthy at HumanLayer, RPI follows the same three-phase structure: research the codebase first, produce a plan, then implement phase by phase. Like this playbook, RPI emphasizes fresh context windows between phases and persisting work as markdown artifacts. Where RPI goes deeper is in **context window management** — Horthy's "Dumb Zone" concept (model performance degrades when context exceeds ~40% capacity) drives the entire workflow design.

**Spec-Driven Development (SDD)** is broader. Popularized in 2025 by Thoughtworks, Amazon's Kiro IDE, and GitHub's Spec Kit, SDD treats the specification as the primary artifact — code is derived from it, not the other way around. The workflow is similar (Specify → Plan → Task → Implement), but SDD is more formal and tool-oriented.

> Both methodologies emerged as responses to the same problem: unstructured "vibe coding" with AI agents produces unreliable results. The convergence across independent practitioners and organizations suggests the core insight is sound — **the bottleneck in AI-assisted development isn't code generation, it's ensuring the model understands what to build before it starts building.**

## Tooling

### Branching Strategy: Git Worktrees

Git worktrees let you check out multiple branches into separate directories. This is useful with AI agents because you can run multiple agents in parallel, each on a different feature branch in its own worktree — without them stepping on each other's files.

```bash
git worktree add ../myproject-feature-x feature-x
git worktree add ../myproject-feature-y feature-y
# When done:
git worktree remove ../myproject-feature-x
```

### Code Intelligence: Language Server Protocol (LSP) Plugins

Claude Code supports LSP plugins that give the agent the same code intelligence that powers VS Code — jump to definition, find references, and real-time type error detection.

Available LSP plugins:

| Language | Plugin | Binary required |
|----------|--------|----------------|
| Python | `pyright-lsp` | `pyright-langserver` |
| Go | `gopls-lsp` | `gopls` |
| TypeScript | `typescript-lsp` | `typescript-language-server` |
| Rust | `rust-analyzer-lsp` | `rust-analyzer` |
| C/C++ | `clangd-lsp` | `clangd` |

### Static Analysis: mypy, ruff, and pyright

- **Ruff** replaces flake8, isort, black, and others in a single fast binary.
- **Mypy** verifies that type annotations are internally consistent.
- **Pyright** (Microsoft's type checker) is faster than mypy and infers more aggressively.

## Advanced: Scaling with Multiple Agents

**Use different models for different roles.** Models trained on different data with different architectures produce largely uncorrelated errors. Use one model to implement and a different model to review.

**Match model capability to task complexity.** Research, planning, and review require synthesis and judgment. Implementation — guided by a detailed plan — is largely mechanical. Use your strongest model for research, planning, and review; use a faster, cheaper model for implementation.

**Enforce hard boundaries between agents.** Different models with different failure modes, different permissions (the implementer edits files, the reviewer can only read and flag issues), and automated gates.

**A more automated workflow:**
1. **Research and planning** — Agent produces `RESEARCH.md` and `PLAN.md`. **Human reviews and approves the plan.** (Highest leverage — errors here cascade into everything downstream.)
2. **Implementation loop** — For each phase, the implementer agent executes, automated gates run, and a reviewer agent (different model) reviews against `PLAN.md`. They iterate until the reviewer is satisfied. No human involvement required.
3. **Pull request** — **Human reviews** the cumulative changes as a PR.

| Plan detail | Safe autonomy level |
|------------|---------------------|
| High-level goal | Human-in-the-loop for every step |
| Phased plan with architecture decisions made | Autonomous per phase, review between phases |
| Detailed plan with file paths and function signatures | Autonomous implementation, human reviews PR |
| Exact specifications with test cases | Fully autonomous with automated verification |

## Extended Context: 1M Context Models

Claude Code offers 1M context variants of both Sonnet and Opus. These models accept up to 1 million tokens of context — roughly 5x the standard 200k window. Best used strategically for:
- **Deep research sessions** exploring large, unfamiliar codebases
- **Complex multi-file refactors** where the model needs to reason across many files at once
- **Long debugging sessions** where the trail of evidence spans many files
