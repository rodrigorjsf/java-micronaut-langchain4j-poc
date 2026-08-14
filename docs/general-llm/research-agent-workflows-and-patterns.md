# Practical Agent Workflows and Patterns

**Scope**: Explore→Plan→Code→Verify loop, Spec→Plan→Execute workflow, two-agent architecture for long-running tasks, instruction specificity, progressive disclosure patterns, and Boris Cherny's simplicity philosophy.  
**Part of**: [LLM Context Engineering: Comprehensive Research Synthesis](research-context-engineering-comprehensive.md)  
**Related scopes**: [Context Rot & Management](research-context-rot-and-management.md) | [Whitespace & Formatting](research-whitespace-and-formatting.md) | [Multilingual Performance](research-multilingual-performance.md)

---

## 13. Practical Agent Workflows and Patterns

### 13.1 The "Explore → Plan → Code → Verify" Loop

**Source**: [Best Practices — Anthropic](https://docs.anthropic.com/en/docs/claude-code/best-practices) | [How Claude Code Works](https://docs.anthropic.com/en/docs/claude-code/how-claude-code-works)

Claude Code's agentic loop follows three phases: **gather context → take action → verify results**. The recommended workflow expands this into four phases:

1. **Explore**: Understand the codebase before changing anything
2. **Plan**: Create a strategy (use Plan Mode for complex tasks)
3. **Code**: Implement the solution
4. **Verify**: Run tests, compare screenshots, validate outputs

> "Claude performs dramatically better when it can verify its own work."

### 13.2 The "Spec → Plan → Execute" Workflow

**Source**: [Harper Reed's LLM Codegen Workflow](https://harper.blog/2025/02/16/my-llm-codegen-workflow-atm/) — widely-shared practitioner workflow, Feb 2025

A three-phase approach validated by extensive practitioner use:

1. **Idea Honing**: Conversational brainstorm → developer-ready `spec.md`
2. **Planning**: Reasoning model generates step-by-step `prompt_plan.md` + `todo.md`
3. **Execution**: Feed prompts iteratively into coding agent

**Key insight**: Discrete loops prevent "getting over your skis" — separating planning from execution prevents the agent from attempting to one-shot complex projects.

### 13.3 Long-Running Agent Harnesses — Two-Agent Architecture

**Source**: [Effective Harnesses for Long-Running Agents — Anthropic Engineering](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)

Multi-context-window agents face two critical failure modes:
1. **One-shotting**: Agent tries to do everything at once, runs out of context mid-implementation
2. **Premature completion**: Agent sees partial progress and declares victory

**Solution — Two-agent architecture**:

| Agent | Role |
|-------|------|
| **Initializer** | First session only. Creates: `init.sh`, `claude-progress.txt`, feature list (JSON), initial git commit |
| **Coding Agent** | Every subsequent session. Makes incremental progress, commits, writes progress updates |

Key technique — **Structured state artifacts**:
- Feature list in **JSON** (not Markdown) — model is less likely to inappropriately modify JSON
- Progress file for inter-session continuity
- Git history as state tracking mechanism

Each coding session starts with:
1. `pwd` → orient to workspace
2. Read progress files and git logs
3. Run basic integration test
4. Choose highest-priority incomplete feature
5. Implement, test, commit, update progress

> "It is unacceptable to remove or edit tests because this could lead to missing or buggy functionality."

### 13.4 Instruction Specificity — Good vs Bad Examples

**Source**: [Prompting Best Practices — Anthropic](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-prompting-best-practices)

| ✅ Specific (actionable) | ❌ Vague (ignored) |
|--------------------------|-------------------|
| "Use 2-space indentation" | "Format code properly" |
| "Run `npm test` before committing" | "Test your changes" |
| "API handlers live in `src/api/handlers/`" | "Keep files organized" |

> "The right altitude is the Goldilocks zone between two common failure modes. At one extreme, engineers hardcode complex, brittle logic. At the other extreme, engineers provide vague, high-level guidance."

### 13.5 Progressive Disclosure Implementation Patterns

| Pattern | Mechanism | Example |
|---------|-----------|---------|
| **Skills** | Description loaded; full content on-demand | `.claude/skills/deploy/SKILL.md` |
| **Path-scoped rules** | Triggered when matching files are read | `.claude/rules/api-design.md` with `paths: ["src/api/**"]` |
| **Subdirectory CLAUDE.md** | Loaded when working in that directory | `packages/frontend/CLAUDE.md` |
| **File imports** | `@path` references expanded when parent loads | `@docs/git-instructions.md` |
| **Dynamic injection** | Shell commands in skills: `` !`gh pr diff` `` | Real-time data at skill invocation |
| **Subagents** | Isolated context, return summaries | Explore agent for codebase research |
| **Auto memory** | First 200 lines loaded; topic files on-demand | `~/.claude/projects/<project>/memory/` |

> **See also**: [§6.3 Progressive Disclosure](research-context-rot-and-management.md#63-progressive-disclosure) in Context Rot & Management for the theoretical foundation of these patterns.

### 13.6 The Boris Cherny Quote on Simplicity

**Source**: [Latent Space Podcast: Claude Code Episode](https://www.latent.space/p/claude-code) — Boris Cherny, Lead Engineer of Claude Code

On memory architecture:
> "We had all these crazy ideas about memory architectures... there's so much literature about this. But in the end, the thing we did is ship the simplest thing, which is a file that has some stuff. And it's auto-read into context."

On compaction:
> "We tried a bunch of different options... truncating old messages and not new messages. And then in the end, we actually just did the simplest thing, which is ask Claude to summarize the previous messages and just return that. When the model is so good, the simple thing usually works."

---

## Cited Sources (this scope)

### Agent Engineering & Best Practices (43–55)

| # | Authors | Title | Venue | Link |
|---|---------|-------|-------|------|
| 43 | Anthropic | Building Effective Agents | Research, 2024 | [anthropic.com](https://www.anthropic.com/research/building-effective-agents) |
| 44 | Anthropic | Effective Harnesses for Long-Running Agents | Blog, 2025 | [anthropic.com](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents) |
| 45 | Anthropic | Contextual Retrieval | Blog, 2024 | [anthropic.com](https://www.anthropic.com/engineering/contextual-retrieval) |
| 46 | Anthropic | Claude Code Best Practices | Docs, 2025 | [docs.anthropic.com](https://docs.anthropic.com/en/docs/claude-code/best-practices) |
| 47 | Anthropic | Claude Code Memory | Docs, 2025 | [docs.anthropic.com](https://docs.anthropic.com/en/docs/claude-code/memory) |
| 48 | Karpathy, A. | Let's Build the GPT Tokenizer (lecture) | YouTube, 2024 | [YouTube](https://youtu.be/zduSFxRajkE) |
| 49 | Ali et al. | Tokenizer Choice for LLM Training | 2023 | [arXiv:2310.08754](https://arxiv.org/abs/2310.08754) |
| 50 | Briakou et al. | Incidental Bilingualism in PaLM | ACL 2023 | [arXiv:2305.10266](https://arxiv.org/abs/2305.10266) |
| 51 | White et al. | Prompt Pattern Catalog | 2023 | [arXiv:2302.11382](https://arxiv.org/abs/2302.11382) |
| 52 | Vatsal & Dubey | Survey on Prompt Engineering | 2024 | [arXiv:2407.12994](https://arxiv.org/abs/2407.12994) |
| 53 | Harper Reed | My LLM Codegen Workflow ATM | Blog, 2025 | [harper.blog](https://harper.blog/2025/02/16/my-llm-codegen-workflow-atm/) |
| 54 | Cherny, B. | Latent Space: Claude Code Episode | Podcast, 2025 | [latent.space](https://www.latent.space/p/claude-code) |
| 55 | Anthropic | How Claude Code Works | Docs, 2025 | [docs.anthropic.com](https://docs.anthropic.com/en/docs/claude-code/how-claude-code-works) |

### Also Cited (cross-scope)

| # | Authors | Title | Venue | Link |
|---|---------|-------|-------|------|
| 1 | Anthropic Applied AI | Effective Context Engineering for AI Agents | Blog, 2025 | [anthropic.com](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) |
| 3 | Liu et al. | Lost in the Middle | TACL 2023 | [arXiv:2307.03172](https://arxiv.org/abs/2307.03172) |
