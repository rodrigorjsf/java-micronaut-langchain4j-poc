# Spec-Driven Development: Write the Spec, Not the Code

**Source:** https://dev.to/bobbyblaine/spec-driven-development-write-the-spec-not-the-code-2p5o
**Category:** Spec-Driven Development
**Platform:** DEV Community (dev.to)

## Summary

A practical practitioner's guide to Spec-Driven Development focused on the three major tools that shipped in early 2026: GitHub Spec Kit, AWS Kiro, and Tessl Framework. The article contrasts "vibe coding" (loose AI prompts) with SDD's structured approach, walks through getting started with Spec Kit step-by-step, and honestly assesses where SDD breaks down (review overhead, exploratory work, AI non-determinism).

## Content

Vibe coding got developers building fast. It also got them rebuilding fast. The pattern: describe what you want, accept the AI's output, ship it. Then spend the next week debugging assumptions the model made because you never stated them. Spec-driven development is the emerging counter-approach, and in early 2026, three major platforms shipped dedicated tooling for it: GitHub's Spec Kit, AWS Kiro, and Tessl Framework. The idea is simple: write a structured specification first, then let the AI generate code that follows it.

## What Spec-Driven Development Actually Is

Spec-driven development (SDD) inverts the vibe coding workflow. Instead of prompting an AI agent with a loose description and iterating on whatever it produces, you write a structured, behavior-oriented specification that defines expected behavior and constraints upfront. The AI agent receives this spec as its primary input and generates code to match.

The core insight is that language models are excellent at pattern completion but bad at mind reading. When you tell an AI agent "build me a REST API for user management," you are leaving thousands of decisions unstated: authentication method, error response format, pagination strategy, rate limiting, input validation rules. The agent fills those gaps with its training data, which may or may not match your actual requirements.

A spec eliminates this guesswork. It makes requirements explicit, testable, and reviewable before a single line of code is generated. Three levels of adoption exist:
- **spec-first**: write specs for immediate tasks
- **spec-anchored**: maintain specs as living documents alongside code
- **spec-as-source**: specs become the canonical artifact, code is entirely generated

Most teams today are at spec-first, which is where the practical payoff starts.

## Three Tools, Three Approaches

GitHub Spec Kit, Kiro, and Tessl each interpret SDD differently.

**GitHub Spec Kit** is the most customizable. It is an open-source CLI that integrates with Copilot, Claude Code, and Gemini CLI through slash commands. The workflow has four phases:
- `/specify` generates a detailed specification from your description
- `/plan` creates a technical implementation plan given your stack and constraints
- `/tasks` breaks the plan into small reviewable chunks
- The agent implements each task sequentially

Spec Kit enforces architectural rules through what it calls a "constitutional foundation"—a set of project-level constraints the agent must obey.

**Kiro** is the simplest entry point. Built as a VS Code extension by AWS, it produces three markdown documents: requirements, design, and tasks. The workflow is linear and lightweight. The tradeoff is that Kiro generated 16 acceptance criteria for a simple bug fix. The overhead can exceed the problem.

**Tessl Framework** is the most ambitious. Still in closed beta, it pursues spec-as-source: the tool reverse-engineers specs from existing code and maintains a 1:1 mapping between spec files and code files, marking generated code with `// GENERATED FROM SPEC - DO NOT EDIT` comments. If it works as intended, developers would maintain only specs, never touching code directly.

The practical reality, across all three tools, is that AI agents still inconsistently follow instructions. A spec reduces the gap between intent and implementation, but it does not eliminate non-determinism. The spec is a guardrail, not a guarantee.

## Getting Started with Spec Kit

Spec Kit is the most accessible tool today because it is open source and works with the agent you are already using.

**Step 1: Install Spec Kit.** It is a CLI tool available via npm. Initialize it in your project with `specify init`. This creates a `.specify/` directory with templates and configuration files.

**Step 2: Write your first spec.** Run `/specify` and describe the feature you want to build. Be specific about behavior, constraints, and edge cases. The agent generates a structured specification you can review, edit, and approve before any code is written.

**Step 3: Generate a plan.** Run `/plan` with your tech stack and constraints. The output is a step-by-step implementation plan that references your spec at every point.

**Step 4: Break it into tasks.** Run `/tasks` to split the plan into small, reviewable work units. Each task has a clear objective and acceptance criteria pulled from the spec.

**Step 5: Implement.** The agent works through tasks sequentially, using the spec and plan as context. You review each completed task against the spec.

### The Difference in Practice

A vibe coding prompt: `"Build a rate limiter middleware for Express."`

A spec-first prompt: `"Implement the rate limiter defined in .spec/features/rate-limiter.md, which specifies a sliding window algorithm, 100 requests per minute per API key, 429 responses with Retry-After headers, and Redis-backed state for horizontal scaling."`

The second prompt leaves no room for the agent to improvise on decisions that should be yours.

The key difference from vibe coding is where you spend your time. In vibe coding, you spend it iterating on code after generation. In SDD, you spend it writing the spec before generation. The total time is often comparable, but the spec is reusable and serves as documentation after the project ships.

### Real-World Examples

- **Anthropic** used GCC test suites to spec a Rust-based C compiler
- **Vercel** used curated shell script tests for a TypeScript bash emulator
- **Pydantic** applied the same approach to a Python sandbox for AI agents

A well-defined spec plus an existing test suite gets an AI agent far on a greenfield build.

## Where SDD Breaks Down

SDD is not a universal improvement. Several friction points temper the hype.

**Review overhead scales with spec verbosity.** Kiro's 16 acceptance criteria for a bug fix is not an edge case. Spec Kit produces extensive markdown for mid-sized features. If reviewing the spec takes longer than reviewing the code would have, the process is working against you.

**Iteration fits poorly into upfront specification.** Exploratory work (prototyping, UI experiments, data pipeline debugging) benefits from fast, loose iteration. Writing a detailed spec before you know what you are building adds latency to a process that should be cheap and fast.

**Non-determinism persists.** Even with a detailed spec, agents sometimes ignore directives or over-interpret them. The spec improves consistency but does not solve the fundamental reliability problem. Vercel's CTO captured this with a useful metaphor: "Software is free now. Free as in puppies." Generation is cheap. Maintenance is where the work lives.

The sweet spot for SDD in its current form is **greenfield features with well-understood requirements**: new API endpoints, CRUD modules, integration layers. It is less useful for exploratory work or for codebases where the existing architecture is poorly documented.

## Key Takeaway

Before your next feature, try writing a one-page spec before prompting your AI agent. Define the inputs, outputs, constraints, and edge cases in plain text. Then pass that spec as context alongside your prompt. You do not need Spec Kit or Kiro to start—a markdown file works.

The goal is to **move the ambiguity from code review to spec review**, where it is cheaper to fix. If the workflow clicks, install Spec Kit and formalize the process.
