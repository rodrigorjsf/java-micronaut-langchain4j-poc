# Research: Claude Code Subagent Definition Best Practices

> **Date**: 2026-03-22
> **Sources**: Official Anthropic documentation, community repositories, prompt engineering guides
> **Scope**: Subagent definition, SKILL.md integration, prompt engineering, execution model, community patterns

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Official Subagent Specification](#2-official-subagent-specification)
3. [Supported Frontmatter Fields](#3-supported-frontmatter-fields)
4. [Subagent File Structure and Scoping](#4-subagent-file-structure-and-scoping)
5. [SKILL.md Agent Integration](#5-skillmd-agent-integration)
6. [Subagent Execution Model](#6-subagent-execution-model)
7. [Model Selection for Subagents](#7-model-selection-for-subagents)
8. [Prompt Engineering for Agent System Prompts](#8-prompt-engineering-for-agent-system-prompts)
9. [Tool Restriction Patterns](#9-tool-restriction-patterns)
10. [Hooks and Lifecycle Events](#10-hooks-and-lifecycle-events)
11. [Persistent Memory for Subagents](#11-persistent-memory-for-subagents)
12. [Error Handling and Guardrails](#12-error-handling-and-guardrails)
13. [Community Examples and Patterns](#13-community-examples-and-patterns)
14. [Anti-Patterns to Avoid](#14-anti-patterns-to-avoid)
15. [Agent Teams vs Subagents](#15-agent-teams-vs-subagents)
16. [Plugin-Shipped Agents](#16-plugin-shipped-agents)
17. [Key Takeaways and Recommendations](#17-key-takeaways-and-recommendations)
18. [Source URLs](#18-source-urls)

---

## 1. Executive Summary

Claude Code subagents are specialized AI assistants that run in isolated context windows with custom system prompts, specific tool access, and independent permissions. They are defined as Markdown files with YAML frontmatter and stored in `agents/` directories at various scopes (project, user, plugin, or CLI).

Key findings:
- **Only `name` and `description` are required** frontmatter fields. Everything else has sensible defaults.
- **Subagents receive ONLY their system prompt** (the markdown body) plus basic environment details — NOT the full Claude Code system prompt or conversation history.
- **`context: fork`** in skills creates a new isolated context where the skill content becomes the task prompt.
- **Model selection** defaults to `inherit` (same as main conversation). Use `haiku` for fast/cheap exploration, `sonnet` for standard work, `opus` for complex reasoning.
- **Subagents cannot spawn other subagents** — this prevents infinite nesting.
- **Community patterns** strongly favor read-only agents for reviews and domain-specific agents for language/framework tasks.

---

## 2. Official Subagent Specification

**Source**: [Anthropic Docs — Subagents](https://docs.anthropic.com/en/docs/claude-code/sub-agents)

Subagents help you:
- **Preserve context** by keeping exploration and implementation out of your main conversation
- **Enforce constraints** by limiting which tools a subagent can use
- **Reuse configurations** across projects with user-level subagents
- **Specialize behavior** with focused system prompts for specific domains
- **Control costs** by routing tasks to faster, cheaper models like Haiku

### Built-in Subagents

Claude Code ships with these built-in subagents:

| Agent | Model | Tools | Purpose |
|-------|-------|-------|---------|
| **Explore** | Haiku (fast) | Read-only (no Write/Edit) | File discovery, code search, codebase exploration |
| **Plan** | Inherits | Read-only (no Write/Edit) | Codebase research for planning mode |
| **general-purpose** | Inherits | All tools | Complex research, multi-step operations, code modifications |
| **Bash** | Inherits | Terminal commands | Running commands in separate context |
| **Claude Code Guide** | Haiku | — | Answering questions about Claude Code features |

### How Delegation Works

Claude automatically delegates tasks based on:
1. The task description in the user's request
2. The `description` field in subagent configurations
3. Current context

To encourage proactive delegation, include phrases like **"use proactively"** in your subagent's `description` field.

---

## 3. Supported Frontmatter Fields

**Source**: [Anthropic Docs — Subagents: Supported Frontmatter Fields](https://docs.anthropic.com/en/docs/claude-code/sub-agents#supported-frontmatter-fields)

| Field | Required | Description | Default |
|-------|----------|-------------|---------|
| `name` | **Yes** | Unique identifier using lowercase letters and hyphens | — |
| `description` | **Yes** | When Claude should delegate to this subagent | — |
| `tools` | No | Tools the subagent can use (allowlist) | Inherits all tools |
| `disallowedTools` | No | Tools to deny (denylist), removed from inherited/specified list | — |
| `model` | No | Model to use: `sonnet`, `opus`, `haiku`, full model ID, or `inherit` | `inherit` |
| `permissionMode` | No | `default`, `acceptEdits`, `dontAsk`, `bypassPermissions`, or `plan` | `default` |
| `maxTurns` | No | Maximum number of agentic turns before stopping | — |
| `skills` | No | Skills to preload into the subagent's context at startup | — |
| `mcpServers` | No | MCP servers available to this subagent | — |
| `hooks` | No | Lifecycle hooks scoped to this subagent | — |
| `memory` | No | Persistent memory scope: `user`, `project`, or `local` | — |
| `background` | No | Set `true` to always run as background task | `false` |
| `effort` | No | Effort level override: `low`, `medium`, `high`, `max` (Opus 4.6 only) | Inherits from session |
| `isolation` | No | Set to `worktree` for isolated git worktree | — |

### Field Interaction Rules

- If both `tools` and `disallowedTools` are set, `disallowedTools` is applied first, then `tools` is resolved against the remaining pool.
- A tool listed in both is removed.
- If `Agent` is omitted from the `tools` list entirely, the agent cannot spawn any subagents.
- `Agent(agent_type)` syntax restricts which subagent types can be spawned (only applies to `--agent` mode).

### CLI-Defined Subagents (JSON format)

Subagents can be passed as JSON when launching Claude Code via the `--agents` flag. These exist only for that session:

```bash
claude --agents '{
  "code-reviewer": {
    "description": "Expert code reviewer. Use proactively after code changes.",
    "prompt": "You are a senior code reviewer. Focus on quality, security, and best practices.",
    "tools": ["Read", "Grep", "Glob", "Bash"],
    "model": "sonnet"
  }
}'
```

In JSON format, use `prompt` for the system prompt (equivalent to the markdown body in file-based subagents).

---

## 4. Subagent File Structure and Scoping

**Source**: [Anthropic Docs — Subagents: Choose the Subagent Scope](https://docs.anthropic.com/en/docs/claude-code/sub-agents#choose-the-subagent-scope)

### File Format

Subagent files are Markdown (`.md`) with YAML frontmatter:

```markdown
---
name: my-agent
description: What this agent does and when to use it
tools: Read, Glob, Grep
model: sonnet
---

You are [role]. When invoked, do [task].

## Process
1. Step one
2. Step two
3. Step three
```

### Scope Priority (highest to lowest)

| Priority | Location | Scope | How to Create |
|----------|----------|-------|---------------|
| 1 (highest) | `--agents` CLI flag | Current session only | Pass JSON at launch |
| 2 | `.claude/agents/` | Current project | Interactive or manual |
| 3 | `~/.claude/agents/` | All your projects | Interactive or manual |
| 4 (lowest) | Plugin's `agents/` directory | Where plugin is enabled | Installed with plugins |

When multiple subagents share the same name, the higher-priority location wins.

### Management

- **`/agents` command**: Interactive interface for creating, viewing, editing, and deleting subagents
- **`claude agents`**: CLI command to list all configured subagents (non-interactive)
- **Project agents** (`.claude/agents/`): Check into version control for team sharing
- **User agents** (`~/.claude/agents/`): Personal agents available in all projects

---

## 5. SKILL.md Agent Integration

**Source**: [Anthropic Docs — Skills: Run Skills in a Subagent](https://docs.anthropic.com/en/docs/claude-code/skills#run-skills-in-a-subagent)

### The `context: fork` Field

Adding `context: fork` to a skill's frontmatter makes it run in an **isolated subagent context**. The skill content becomes the prompt that drives the subagent. It **will not** have access to your conversation history.

```yaml
---
name: deep-research
description: Research a topic thoroughly
context: fork
agent: Explore
---

Research $ARGUMENTS thoroughly:
1. Find relevant files using Glob and Grep
2. Read and analyze the code
3. Summarize findings with specific file references
```

### The `agent` Field

The `agent` field specifies **which subagent configuration** to use when `context: fork` is set. Options include:
- Built-in agents: `Explore`, `Plan`, `general-purpose`
- Any custom subagent name from `.claude/agents/`
- If omitted, defaults to `general-purpose`

### How It Works

When a skill with `context: fork` runs:
1. A new isolated context is created
2. The subagent receives the skill content as its prompt
3. The `agent` field determines the execution environment (model, tools, permissions)
4. Results are summarized and returned to the main conversation

### Skills ↔ Subagents Relationship

| Approach | System Prompt | Task | Also Loads |
|----------|---------------|------|------------|
| Skill with `context: fork` | From agent type (Explore, Plan, etc.) | SKILL.md content | CLAUDE.md |
| Subagent with `skills` field | Subagent's markdown body | Claude's delegation message | Preloaded skills + CLAUDE.md |

### Preloading Skills into Subagents

Use the `skills` field in subagent frontmatter to inject skill content at startup:

```yaml
---
name: api-developer
description: Implement API endpoints following team conventions
skills:
  - api-conventions
  - error-handling-patterns
---
```

**Important**: The full content of each skill is injected into the subagent's context, not just made available for invocation. Subagents don't inherit skills from the parent conversation — you must list them explicitly.

### Skill Frontmatter Reference

| Field | Description |
|-------|-------------|
| `name` | Display name (lowercase letters, numbers, hyphens, max 64 chars) |
| `description` | What the skill does — Claude uses this for auto-invocation |
| `argument-hint` | Hint for autocomplete (e.g., `[issue-number]`) |
| `disable-model-invocation` | `true` = only user can invoke via `/name` |
| `user-invocable` | `false` = only Claude can invoke (background knowledge) |
| `allowed-tools` | Tools allowed without asking permission when skill is active |
| `model` | Model override when skill is active |
| `effort` | Effort level override when skill is active |
| `context` | Set to `fork` to run in a forked subagent context |
| `agent` | Which subagent type to use when `context: fork` is set |
| `hooks` | Hooks scoped to this skill's lifecycle |

### Dynamic Context Injection

The `` !`<command>` `` syntax runs shell commands before skill content is sent to Claude:

```yaml
---
name: pr-summary
description: Summarize changes in a pull request
context: fork
agent: Explore
allowed-tools: Bash(gh *)
---

## Pull request context
- PR diff: !`gh pr diff`
- PR comments: !`gh pr view --comments`

## Your task
Summarize this pull request...
```

The commands execute immediately (preprocessing), and output replaces the placeholder.

---

## 6. Subagent Execution Model

**Source**: [Anthropic Docs — Subagents](https://docs.anthropic.com/en/docs/claude-code/sub-agents)

### Context Isolation

- Each subagent runs in its **own context window** with a custom system prompt
- Subagents receive **only** their system prompt (markdown body) plus basic environment details (working directory, etc.)
- They do **NOT** receive the full Claude Code system prompt or the parent conversation history
- CLAUDE.md files and project memory still load through the normal message flow

### Execution Modes

| Mode | Behavior | Permission Handling |
|------|----------|-------------------|
| **Foreground** | Blocks main conversation until complete | Permission prompts pass through to user |
| **Background** | Runs concurrently | Pre-approved permissions; auto-denies others |

### Key Constraints

- **Subagents cannot spawn other subagents** — prevents infinite nesting
- **Background subagents** auto-deny any permissions not pre-approved
- If a background subagent needs to ask clarifying questions, the tool call fails but the subagent continues

### Resuming Subagents

Each subagent invocation creates a new instance with fresh context. To continue existing work:
- Ask Claude to "continue that code review" — it resumes with full conversation history
- Claude uses `SendMessage` tool with the agent's ID to resume
- Subagent transcripts persist at `~/.claude/projects/{project}/{sessionId}/subagents/agent-{agentId}.jsonl`

### Auto-Compaction

- Subagents support automatic compaction at ~95% capacity
- Override with `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` environment variable
- Compaction events are logged in subagent transcript files

### Invocation Methods

1. **Natural language**: Name the subagent in your prompt — Claude decides whether to delegate
2. **@-mention**: `@"code-reviewer (agent)"` — guarantees that subagent runs
3. **Session-wide**: `claude --agent code-reviewer` — the whole session uses that agent's config
4. **Settings default**: `{"agent": "code-reviewer"}` in `.claude/settings.json`

---

## 7. Model Selection for Subagents

**Source**: [Anthropic Docs — Model Config](https://docs.anthropic.com/en/docs/claude-code/model-config)

### Available Model Aliases

| Alias | Maps To | Best For |
|-------|---------|----------|
| `haiku` | Claude Haiku 4.5 | Fast, cheap tasks; exploration; simple analysis |
| `sonnet` | Claude Sonnet 4.6 | Standard coding tasks; reviews; daily work |
| `opus` | Claude Opus 4.6 | Complex reasoning; architecture; planning |
| `inherit` | Same as main conversation | Default behavior |
| `sonnet[1m]` | Sonnet with 1M context | Long sessions with large codebases |
| `opus[1m]` | Opus with 1M context | Long sessions with complex reasoning |

### When to Use Each Model

| Use Case | Recommended Model | Rationale |
|----------|-------------------|-----------|
| Code exploration / search | `haiku` | Fast, low-latency; read-only work doesn't need complex reasoning |
| Code review | `sonnet` | Good balance of quality and speed for checklist-based analysis |
| Architecture decisions | `opus` | Complex trade-off analysis requires deeper reasoning |
| Debugging | `sonnet` | Needs tool access and reasoning but speed matters |
| Security review | `sonnet` | Pattern matching + reasoning; doesn't need Opus-level depth |
| Data analysis | `sonnet` | SQL generation and interpretation; standard reasoning |
| Documentation | `sonnet` or `haiku` | Depends on complexity of content |
| Simple file operations | `haiku` | Routine, mechanical tasks |

### Environment Variable Override

```bash
# Override the model used for all subagents
CLAUDE_CODE_SUBAGENT_MODEL=claude-sonnet-4-6
```

### Cost Considerations

- **Haiku** is significantly cheaper per token than Sonnet
- **Sonnet** is the sweet spot for most subagent tasks
- **Opus** should be reserved for tasks that genuinely require deep reasoning
- The built-in **Explore** agent already defaults to Haiku for cost efficiency
- Prompt caching is enabled by default and applies to subagents

### Effort Levels

Effort levels control adaptive reasoning depth. Set via the `effort` frontmatter field:

| Level | Behavior | Best For |
|-------|----------|----------|
| `low` | Minimal thinking; fast responses | Simple, mechanical tasks |
| `medium` | Balanced thinking | Standard code tasks |
| `high` | Deep thinking | Complex problems |
| `max` | No constraint on thinking tokens (Opus 4.6 only) | Hardest problems |

---

## 8. Prompt Engineering for Agent System Prompts

**Sources**:
- [Anthropic Docs — Prompting Best Practices](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-prompting-best-practices)
- [Anthropic Docs — Subagents: Example Subagents](https://docs.anthropic.com/en/docs/claude-code/sub-agents#example-subagents)
- [Community: everything-claude-code](https://github.com/affaan-m/everything-claude-code)

### Core Principles for Agent Prompts

1. **Be clear and direct** — Subagents lack context from the parent conversation. The system prompt is ALL they have.
2. **Define a role** — Start with "You are a [specific role]" to focus behavior and tone.
3. **Provide sequential steps** — Use numbered lists for the process the agent should follow.
4. **Include a review checklist** — Structured criteria prevent agents from missing important checks.
5. **Specify output format** — Tell the agent exactly how to structure findings or results.
6. **Include examples** — Code examples showing good/bad patterns help ground behavior.

### Effective System Prompt Structure

Based on analysis of official examples and community patterns:

```markdown
---
name: agent-name
description: [Role] specialist. [When to use]. Use [proactively/after X].
tools: [Minimal required tool list]
model: [appropriate model]
---

You are a [specific role] specializing in [domain].

## Your Role
- [Responsibility 1]
- [Responsibility 2]

## Process

When invoked:
1. [First step — gather context]
2. [Second step — analyze]
3. [Third step — act]
4. [Fourth step — verify]
5. [Fifth step — report]

## Checklist / Criteria
### Category 1 (CRITICAL)
- [Check item]
- [Check item]

### Category 2 (HIGH)
- [Check item]

## Output Format
[Exact format specification]

## Approval / Success Criteria
- [When to approve]
- [When to flag]
```

### Description Field Best Practices

The `description` field is **critical** — Claude uses it to decide when to delegate. Good descriptions:
- State the agent's specialization clearly
- Include when Claude should use it
- Use actionable language like "Use proactively after..." or "Use when encountering..."

**Good examples from community:**
- `"Expert code review specialist. Proactively reviews code for quality, security, and maintainability. Use immediately after writing or modifying code."`
- `"Software architecture specialist for system design, scalability, and technical decision-making. Use PROACTIVELY when planning new features."`
- `"Security vulnerability detection and remediation specialist. Use PROACTIVELY after writing code that handles user input, authentication, API endpoints, or sensitive data."`

**Bad examples:**
- `"Reviews code"` — too vague, Claude won't know when to delegate
- `"A helpful assistant for coding tasks"` — overlaps with everything, will trigger too often
- `"Use this for stuff"` — provides no routing signal

### Prompt Engineering Tips from Anthropic's Agentic Systems Guide

From the [official prompting best practices](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-prompting-best-practices):

> "Claude's latest models demonstrate significantly improved native subagent orchestration capabilities. These models can recognize when tasks would benefit from delegating work to specialized subagents and do so proactively without requiring explicit instruction."

Key recommendations:
1. **Ensure well-defined subagent tools** — Have subagent tools available and described in tool definitions
2. **Let Claude orchestrate naturally** — Claude will delegate appropriately without explicit instruction
3. **Watch for overuse** — Claude Opus 4.6 may spawn subagents in situations where a simpler, direct approach would suffice
4. **Prefer general instructions over prescriptive steps** — "think thoroughly" often produces better reasoning than hand-written step-by-step plans
5. **Opus 4.6 is more responsive to system prompts** — Dial back aggressive language ("CRITICAL: You MUST...") in favor of normal prompting ("Use this tool when...")

### Confidence-Based Filtering Pattern

From the community `code-reviewer` agent, a pattern worth replicating:

```markdown
## Confidence-Based Filtering

**IMPORTANT**: Do not flood the review with noise. Apply these filters:

- **Report** if you are >80% confident it is a real issue
- **Skip** stylistic preferences unless they violate project conventions
- **Skip** issues in unchanged code unless they are CRITICAL security issues
- **Consolidate** similar issues
- **Prioritize** issues that could cause bugs, security vulnerabilities, or data loss
```

This reduces noise and makes agent output actionable.

---

## 9. Tool Restriction Patterns

**Source**: [Anthropic Docs — Subagents: Control Subagent Capabilities](https://docs.anthropic.com/en/docs/claude-code/sub-agents#control-subagent-capabilities)

### Available Tools

From the [Tools Reference](https://docs.anthropic.com/en/docs/claude-code/tools-reference):

| Tool | Description | Permission Required |
|------|-------------|-------------------|
| `Agent` | Spawns a subagent | No |
| `Bash` | Executes shell commands | Yes |
| `Edit` | Makes targeted file edits | Yes |
| `Glob` | Finds files by pattern | No |
| `Grep` | Searches file contents | No |
| `Read` | Reads file contents | No |
| `Write` | Creates or overwrites files | Yes |
| `WebFetch` | Fetches URL content | Yes |
| `WebSearch` | Performs web searches | Yes |
| `Skill` | Executes a skill | Yes |
| `LSP` | Code intelligence via language servers | No |

### Common Tool Profiles

| Agent Type | Recommended Tools | Rationale |
|------------|-------------------|-----------|
| **Read-only reviewer** | `Read, Grep, Glob, Bash` | Can inspect code but not modify it |
| **Code modifier** | `Read, Edit, Bash, Grep, Glob` | Can both analyze and fix issues |
| **Explorer/researcher** | `Read, Grep, Glob` | Pure exploration, no side effects |
| **Full-capability** | (inherit all) | For complex multi-step operations |

### Allowlist vs Denylist

```yaml
# Allowlist: ONLY these tools
tools: Read, Grep, Glob, Bash

# Denylist: everything EXCEPT these
disallowedTools: Write, Edit
```

### Restricting Spawnable Subagents

```yaml
# Only allow spawning 'worker' and 'researcher'
tools: Agent(worker, researcher), Read, Bash

# Allow spawning any subagent
tools: Agent, Read, Bash
```

---

## 10. Hooks and Lifecycle Events

**Source**: [Anthropic Docs — Hooks](https://docs.anthropic.com/en/docs/claude-code/hooks)

### Hooks in Subagent Frontmatter

Define hooks directly in the subagent's markdown file. These hooks only run while that specific subagent is active:

```yaml
---
name: db-reader
description: Execute read-only database queries
tools: Bash
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/validate-readonly-query.sh"
---
```

### Supported Hook Events for Subagents

| Event | Matcher Input | When It Fires |
|-------|---------------|---------------|
| `PreToolUse` | Tool name | Before the subagent uses a tool |
| `PostToolUse` | Tool name | After the subagent uses a tool |
| `Stop` | (none) | When the subagent finishes (converted to `SubagentStop` at runtime) |

### Project-Level Subagent Lifecycle Hooks

Configure in `settings.json` to respond to subagent lifecycle events:

```json
{
  "hooks": {
    "SubagentStart": [
      {
        "matcher": "db-agent",
        "hooks": [
          { "type": "command", "command": "./scripts/setup-db-connection.sh" }
        ]
      }
    ],
    "SubagentStop": [
      {
        "hooks": [
          { "type": "command", "command": "./scripts/cleanup-db-connection.sh" }
        ]
      }
    ]
  }
}
```

### Hook Types

- **`command`**: Execute a shell command (receives JSON on stdin, communicates via exit codes)
- **`http`**: Send event JSON as POST request
- **`prompt`**: Evaluate a prompt with a Claude model (yes/no decision)
- **`agent`**: Spawn a subagent verifier with tools for complex verification

### Exit Codes

- **0**: Allow the operation
- **2**: Block the operation (feeds error message back to Claude via stderr)

---

## 11. Persistent Memory for Subagents

**Source**: [Anthropic Docs — Subagents: Enable Persistent Memory](https://docs.anthropic.com/en/docs/claude-code/sub-agents#enable-persistent-memory)

### Memory Scopes

| Scope | Location | Use When |
|-------|----------|----------|
| `user` | `~/.claude/agent-memory/<name>/` | Learnings should apply across all projects |
| `project` | `.claude/agent-memory/<name>/` | Knowledge is project-specific, shareable via VCS |
| `local` | `.claude/agent-memory-local/<name>/` | Project-specific but should NOT be committed |

### How Memory Works

When `memory` is enabled:
1. The subagent's system prompt includes instructions for reading/writing to the memory directory
2. First 200 lines of `MEMORY.md` in the memory directory are included in context
3. If `MEMORY.md` exceeds 200 lines, the subagent is instructed to curate it
4. `Read`, `Write`, and `Edit` tools are automatically enabled

### Memory Best Practices

- **`project`** is the recommended default scope (shareable via VCS)
- Ask the subagent to consult its memory before starting work
- Ask the subagent to update its memory after completing a task
- Include memory instructions directly in the markdown file:

```markdown
Update your agent memory as you discover codepaths, patterns, library
locations, and key architectural decisions. This builds up institutional
knowledge across conversations.
```

---

## 12. Error Handling and Guardrails

### Tool Validation with Hooks

The most robust pattern for guardrails is using `PreToolUse` hooks that validate operations:

```yaml
---
name: safe-deployer
description: Deploy application with safety checks
tools: Bash
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/validate-deploy-command.sh"
---
```

### Permission Modes

| Mode | Behavior |
|------|----------|
| `default` | Standard permission checking with prompts |
| `acceptEdits` | Auto-accept file edits |
| `dontAsk` | Auto-deny permission prompts (allowed tools still work) |
| `bypassPermissions` | Skip permission prompts |
| `plan` | Plan mode (read-only exploration) |

**Note**: If the parent uses `bypassPermissions`, this takes precedence and cannot be overridden by the subagent.

### maxTurns Guardrail

Set `maxTurns` to prevent runaway agents:

```yaml
---
name: quick-checker
description: Quick code quality check
maxTurns: 10
---
```

### Isolation with Worktrees

Use `isolation: worktree` to run the subagent in a temporary git worktree:

```yaml
---
name: risky-refactor
description: Attempt a large refactoring safely
isolation: worktree
---
```

The worktree is automatically cleaned up if the subagent makes no changes.

### Disabling Specific Subagents

In settings:
```json
{
  "permissions": {
    "deny": ["Agent(Explore)", "Agent(my-custom-agent)"]
  }
}
```

Or via CLI: `claude --disallowedTools "Agent(Explore)"`

---

## 13. Community Examples and Patterns

### everything-claude-code (97k+ stars)

**Source**: [github.com/affaan-m/everything-claude-code](https://github.com/affaan-m/everything-claude-code)

This repository includes 27+ agent definitions in its `agents/` directory, covering:

| Agent | Model | Tools | Pattern |
|-------|-------|-------|---------|
| `code-reviewer` | sonnet | Read, Grep, Glob, Bash | Read-only review with confidence filtering |
| `architect` | opus | Read, Grep, Glob | Read-only planning with trade-off analysis |
| `security-reviewer` | sonnet | Read, Write, Edit, Bash, Grep, Glob | Full-capability security audit |
| `debugger` | (inherit) | Read, Edit, Bash, Grep, Glob | Analysis + fix workflow |
| `typescript-reviewer` | (implied) | Read, Grep, Glob, Bash | Language-specific review |
| `python-reviewer` | (implied) | Read, Grep, Glob, Bash | Language-specific review |
| `rust-reviewer` | (implied) | Read, Grep, Glob, Bash | Language-specific review |
| `planner` | (implied) | Read, Grep, Glob | Planning and research |
| `doc-updater` | (implied) | Read, Write, Edit, Bash, Grep, Glob | Documentation maintenance |
| `tdd-guide` | (implied) | — | Test-driven development guidance |

#### Key Patterns Observed

1. **Read-only agents dominate**: Most review/analysis agents restrict to `Read, Grep, Glob, Bash` — preventing accidental modifications
2. **Language-specific reviewers**: Dedicated agents for TypeScript, Python, Rust, Java, Kotlin, Go, Flutter, C++ — each with domain-specific checklists
3. **Build error resolvers**: Language-specific agents for resolving build errors (e.g., `rust-build-resolver`, `go-build-resolver`)
4. **Opus for architecture, Sonnet for everything else**: The `architect` agent uses `opus` for deeper reasoning; reviewers use `sonnet`
5. **Detailed checklists**: Every review agent includes prioritized checklists (CRITICAL → LOW severity)
6. **Structured output formats**: Agents specify exact output format with tables and summary sections

#### Community Code Reviewer Example (Abbreviated)

```markdown
---
name: code-reviewer
description: Expert code review specialist. Use immediately after writing
  or modifying code. MUST BE USED for all code changes.
tools: ["Read", "Grep", "Glob", "Bash"]
model: sonnet
---

You are a senior code reviewer ensuring high standards of code quality and security.

## Review Process
1. Gather context — Run `git diff --staged` and `git diff`
2. Understand scope — Identify which files changed
3. Read surrounding code — Don't review changes in isolation
4. Apply review checklist — CRITICAL to LOW
5. Report findings — Only >80% confidence issues

## Review Checklist
### Security (CRITICAL)
- Hardcoded credentials
- SQL injection
- XSS vulnerabilities
[...]

## Review Output Format
[CRITICAL] Hardcoded API key in source
File: src/api/client.ts:42
Issue: API key exposed in source code
Fix: Move to environment variable
```

### Other Notable Community Repositories

| Repository | Stars | Focus |
|------------|-------|-------|
| [awesome-claude-code](https://github.com/hesreallyhim/awesome-claude-code) | 30k+ | Curated list of skills, hooks, agents, plugins |
| [antigravity-awesome-skills](https://github.com/sickn33/antigravity-awesome-skills) | 26k+ | 1,300+ agentic skills library |
| [planning-with-files](https://github.com/OthmanAdi/planning-with-files) | 16k+ | Manus-style persistent markdown planning |
| [awesome-agent-skills](https://github.com/VoltAgent/awesome-agent-skills) | 12k+ | 500+ skills from community and official teams |
| [agent-orchestrator](https://github.com/ComposioHQ/agent-orchestrator) | 5k+ | Parallel coding agent orchestration |
| [claude-code-guide](https://github.com/zebbern/claude-code-guide) | 3.7k+ | Setup, commands, workflows, agents, tips |

---

## 14. Anti-Patterns to Avoid

### From Official Documentation

1. **Overly aggressive delegation prompts**: Claude Opus 4.6 is responsive to system prompts. Replace `"CRITICAL: You MUST use this tool when..."` with `"Use this tool when..."` — the older aggressive style causes overtriggering.
   *Source*: [Anthropic Prompting Best Practices](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-prompting-best-practices)

2. **Too many skills/agents**: Skill descriptions are loaded into context (2% of context window budget). Too many can exceed the character limit. Run `/context` to check for warnings.
   *Source*: [Anthropic Docs — Skills: Troubleshooting](https://docs.anthropic.com/en/docs/claude-code/skills#troubleshooting)

3. **Using subagents for simple grep operations**: Claude Opus 4.6 may spawn subagents when a direct grep call is faster and sufficient. Add guidance: "Only use subagents for tasks that genuinely require multi-step reasoning or tool access beyond simple search."
   *Source*: [Anthropic Prompting Best Practices — Subagent Orchestration](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-prompting-best-practices)

4. **Not restricting tools**: Giving an agent all tools when it only needs `Read` and `Grep` risks unintended modifications and wastes context on unused tool descriptions.

5. **Vague descriptions**: If the description doesn't clearly signal when to use the agent, Claude either never delegates to it or delegates too often.

### From Community Patterns

6. **God agents**: One agent that does everything defeats the purpose. Keep agents focused on one domain.

7. **No output format specification**: Without a specified output format, agent results are inconsistent and hard to act on.

8. **Ignoring the parent context gap**: Subagents don't see parent conversation history. If the agent needs specific context, it must be in the system prompt or gathered via tools.

9. **Missing first-step context gathering**: The best community agents always start with a "gather context" step (e.g., `git diff`, explore codebase) before doing analysis.

10. **Not using `maxTurns`**: Agents without turn limits can run indefinitely, consuming tokens without producing useful results.

---

## 15. Agent Teams vs Subagents

**Source**: [Anthropic Docs — Agent Teams](https://docs.anthropic.com/en/docs/claude-code/agent-teams)

| Feature | Subagents | Agent Teams |
|---------|-----------|-------------|
| **Context** | Own window; results return to caller | Own window; fully independent |
| **Communication** | Report back to main agent only | Teammates message each other directly |
| **Coordination** | Main agent manages all work | Shared task list with self-coordination |
| **Best for** | Focused tasks where only results matter | Complex work requiring discussion |
| **Token cost** | Lower (results summarized) | Higher (each teammate is separate instance) |
| **Spawning** | Cannot spawn other subagents | Can communicate with any teammate |

### When to Use Each

- **Subagents**: Quick, focused workers that report back (reviews, exploration, targeted fixes)
- **Agent Teams**: Complex work requiring parallel investigation, cross-layer coordination, competing hypotheses
- **Main conversation**: Frequent back-and-forth, iterative refinement, quick changes

---

## 16. Plugin-Shipped Agents

**Source**: [Anthropic Docs — Plugins Reference: Agents](https://docs.anthropic.com/en/docs/claude-code/plugins-reference)

### Plugin Agent Structure

Place agent files in the `agents/` directory of the plugin root:

```
my-plugin/
├── .claude-plugin/
│   └── plugin.json
├── agents/
│   ├── reviewer.md
│   └── tester.md
├── skills/
│   └── my-skill/
│       └── SKILL.md
└── hooks/
    └── hooks.json
```

### Supported Fields for Plugin Agents

Plugin agents support: `name`, `description`, `model`, `effort`, `maxTurns`, `tools`, `disallowedTools`, `skills`, `memory`, `background`, `isolation`

### Security Restrictions

For security reasons, plugin-shipped agents **cannot** use:
- `hooks` — no lifecycle hooks
- `mcpServers` — no MCP server definitions
- `permissionMode` — no permission overrides

### Plugin Agent Naming

Plugin agents appear in the `/agents` interface as `<plugin-name>:<agent-name>` and can be invoked with `claude --agent <plugin-name>:<agent-name>`.

---

## 17. Key Takeaways and Recommendations

### For Agent Definition

1. **Start minimal**: Only `name` and `description` are required. Add complexity as needed.
2. **Restrict tools to minimum necessary**: Read-only agents are safer and cheaper than full-capability ones.
3. **Use `sonnet` as default model**: It's the best cost/quality balance. Reserve `opus` for architecture and complex reasoning.
4. **Write actionable descriptions**: Include "Use proactively when..." or "Use after..." to help Claude route tasks.
5. **Structure prompts with clear sections**: Role → Process → Checklist → Output Format → Approval Criteria.
6. **Include confidence filtering**: Prevent agents from flooding with low-confidence noise.
7. **Set `maxTurns` for safety**: Prevent runaway agents that consume tokens endlessly.

### For Skills with `context: fork`

8. **Use `context: fork` for task-oriented skills** that should run in isolation.
9. **Choose the right `agent` type**: `Explore` for read-only research, `general-purpose` for tasks needing modifications.
10. **Use `!`command`` for dynamic context injection**: Preprocessing shell commands enrich prompts with live data.

### For Memory and Learning

11. **Use `memory: project` by default**: Shareable via version control, builds institutional knowledge.
12. **Include memory instructions in the prompt**: Tell the agent to read memory before starting and update after finishing.

### For Error Handling

13. **Use `PreToolUse` hooks for validation**: Conditional rules on tool usage provide dynamic guardrails.
14. **Use `isolation: worktree` for risky operations**: Git worktrees provide safe sandboxes.
15. **Use `dontAsk` permission mode** for agents that should fail gracefully instead of prompting.

### For Organization

16. **Project agents in `.claude/agents/`**: Check into version control for team sharing.
17. **Personal agents in `~/.claude/agents/`**: Cross-project utilities like general code review.
18. **Language/framework-specific agents**: Create dedicated agents for each technology in your stack.
19. **Compose agents with the `skills` field**: Inject domain knowledge from skills into agent context.

---

## 18. Source URLs

### Official Anthropic Documentation

| Document | URL |
|----------|-----|
| Subagents | https://docs.anthropic.com/en/docs/claude-code/sub-agents |
| Skills | https://docs.anthropic.com/en/docs/claude-code/skills |
| Model Config | https://docs.anthropic.com/en/docs/claude-code/model-config |
| Hooks | https://docs.anthropic.com/en/docs/claude-code/hooks |
| Tools Reference | https://docs.anthropic.com/en/docs/claude-code/tools-reference |
| Agent Teams | https://docs.anthropic.com/en/docs/claude-code/agent-teams |
| Plugins Reference | https://docs.anthropic.com/en/docs/claude-code/plugins-reference |
| Prompt Engineering | https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/claude-prompting-best-practices |

### Community Repositories

| Repository | URL |
|------------|-----|
| everything-claude-code (97k★) | https://github.com/affaan-m/everything-claude-code |
| awesome-claude-code (30k★) | https://github.com/hesreallyhim/awesome-claude-code |
| antigravity-awesome-skills (26k★) | https://github.com/sickn33/antigravity-awesome-skills |
| planning-with-files (16k★) | https://github.com/OthmanAdi/planning-with-files |
| awesome-agent-skills (12k★) | https://github.com/VoltAgent/awesome-agent-skills |
| agent-orchestrator (5k★) | https://github.com/ComposioHQ/agent-orchestrator |
| claude-skills (6k★) | https://github.com/alirezarezvani/claude-skills |
| claude-code-guide (3.7k★) | https://github.com/zebbern/claude-code-guide |

### Agent Skills Standard

| Resource | URL |
|----------|-----|
| Agent Skills Open Standard | https://agentskills.io |

---

## Appendix A: Complete Agent Definition Template

```markdown
---
name: my-agent-name
description: >-
  [Role] specialist for [domain]. Use proactively when [trigger condition].
  [Additional routing signals].
tools: Read, Grep, Glob, Bash
model: sonnet
effort: medium
maxTurns: 20
memory: project
---

You are a [specific role] specializing in [domain expertise].

## Your Role
- [Primary responsibility]
- [Secondary responsibility]
- [Constraint or boundary]

## Process

When invoked:
1. **Gather context** — [How to understand what you're working with]
2. **Analyze** — [What to look for, what criteria to apply]
3. **Act** — [What action to take based on analysis]
4. **Verify** — [How to confirm the action was correct]
5. **Report** — [What to communicate back]

## Checklist

### Critical Issues
- [Must-flag item 1]
- [Must-flag item 2]

### High Priority
- [Should-flag item 1]
- [Should-flag item 2]

### Medium Priority
- [Nice-to-catch item]

## Output Format

[Specify exact structure — tables, severity levels, file references, code examples]

## Success Criteria
- [When to approve / pass]
- [When to warn]
- [When to block / fail]

## Notes
- Only report issues with >80% confidence
- Consolidate similar issues
- Adapt to project-specific conventions from CLAUDE.md
```

## Appendix B: Complete Skill with `context: fork` Template

```markdown
---
name: my-forked-skill
description: >-
  [What this skill does]. Use when [trigger condition].
context: fork
agent: Explore
allowed-tools: Read, Grep, Glob
---

## Context
- Current changes: !`git diff --name-only`
- Branch: !`git branch --show-current`

## Task

$ARGUMENTS

## Process
1. [Step 1]
2. [Step 2]
3. [Step 3]

## Output Requirements
- [Format specification]
- [What to include]
- [What to exclude]
```
