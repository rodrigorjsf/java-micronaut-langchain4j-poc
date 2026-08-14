# Agent Skills Standard — Official Documentation

This directory contains official documentation mirrored from **[agentskills.io](https://agentskills.io)**, the open standard for extending AI agents with skills.

The Agent Skills standard is widely adopted across GenAI agents (Cursor, Claude Code, Codex, and others). It defines the portable, version-controlled format that allows skills to work across any compliant agent implementation.

**Related documentation**: [Skill Authoring Best Practices](../skill-authoring-best-practices.md) (universal guide) | [Extend Claude with Skills](../../claude-code/skills/extend-claude-with-skills.md) (Claude Code tutorial) | [Research: Skills Format Comparison](../../claude-code/skills/research-claude-code-skills-format.md)

## Documents

| File | Title | Description |
|------|-------|-------------|
| [agentskills-what-are-skills.md](./agentskills-what-are-skills.md) | What are skills? | Core concepts: what a skill is, how it is structured, and why the standard exists |
| [agentskills-specification.md](./agentskills-specification.md) | Specification | Full technical specification — file format, frontmatter fields, directory layout, and protocol |
| [agentskills-best-practices.md](./agentskills-best-practices.md) | Best practices for skill creators | Authoring guidelines: naming, scope, descriptions, and when to create a skill |
| [agentskills-using-scripts.md](./agentskills-using-scripts.md) | Using scripts in skills | How to bundle executable scripts inside a skill and reference them from `SKILL.md` |
| [agentskills-optimizing-descriptions.md](./agentskills-optimizing-descriptions.md) | Optimizing skill descriptions | Techniques for writing `description` fields that maximize correct agent invocation |
| [agentskills-evaluating-skills.md](./agentskills-evaluating-skills.md) | Evaluating skill output quality | How to measure and improve the quality of agent behaviour when a skill is applied |

## Source

All documents originate from the official Agent Skills documentation site:

- **Website**: <https://agentskills.io>
- **Full doc index**: <https://agentskills.io/llms.txt>

> These files are kept as a local reference for AI agents working in this repository. When in doubt, consult the live site for the most up-to-date version of the standard.
