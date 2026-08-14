# Research, Plan, Implement (RPI) - Agentic Engineering

**Source:** https://deepwiki.com/humanlayer/advanced-context-engineering-for-coding-agents/3-the-research-plan-implement-workflow
**Original Repository:** https://github.com/humanlayer/advanced-context-engineering-for-coding-agents
**Category:** Agentic Engineering

## Summary

The Research-Plan-Implement (RPI) workflow, developed by Dex Horthy at HumanLayer, divides AI coding work into three sequential phases, each producing a compacted artifact that serves as the input for the next phase. It is part of the Frequent Intentional Compaction (FIC) methodology and addresses the core constraint that LLM output quality depends entirely on context quality. Human review at the Research and Plan phases provides the highest leverage for catching errors before they cascade into code.

## Content

### Purpose and Scope

This document describes the three-phase workflow (Research → Plan → Implement) that forms the operational backbone of the Frequent Intentional Compaction (FIC) methodology. The workflow structures AI coding work into discrete phases with explicit context compaction boundaries, enabling sustainable productivity in complex codebases.

### Workflow Overview

The Research-Plan-Implement workflow divides AI coding work into three sequential phases, each producing a compacted artifact that serves as the input for the next phase. This structure addresses the fundamental constraint that LLM output quality depends entirely on context quality in a stateless function model.

### Phase Characteristics and Artifacts

Each phase has distinct characteristics optimized for different aspects of context management:

| Phase | Primary Goal | Context Consumption | Output Artifact | Typical Size | Human Review Leverage |
|-------|-------------|--------------------|-----------------|--------------|-----------------------|
| Research | Understand codebase structure and information flow | High (file searches, code reading) | `research_doc.md` | ~200 lines | ⭐⭐⭐⭐ (highest) |
| Plan | Define precise implementation steps | Medium (research doc, architecture analysis) | `implementation_plan.md` | ~200 lines | ⭐⭐⭐ (high) |
| Implement | Execute plan and verify | Low-Medium (plan doc, test outputs) | Code files + tests | Variable | ⭐ (lowest) |

**The leverage model shows that errors compound as you move through phases:**
- Bad research → 1000s of bad lines of code
- Bad plan → 100s of bad lines of code
- Bad code → 1 bad line of code

### Phase Transition Mechanics

#### Research Phase Initiation

The research phase begins with a fresh context window containing only the problem definition. Subagents perform noisy operations (glob, grep, read) in isolated contexts, returning compacted summaries to prevent context pollution.

The research agent is invoked using a prompt template which structures how subagents are used and how results are compacted.

#### Plan Phase Initiation

The plan phase starts with a clean context window containing the research doc and problem definition. No raw file contents or search results pollute the context.

#### Implementation Phase Initiation

The implementation phase starts with a clean context window containing only the implementation plan. For complex tasks requiring multiple compaction cycles, the agent updates `progress.md` to track status.

### Context Optimization Through Phase Structure

The three-phase structure optimizes context usage by ensuring each phase starts with a clean, correctly-scoped context window. The 40-60% target utilization during active work prevents context overflow while maintaining sufficient working room.

### Concrete Implementation Examples

#### Research Document Example

```
thoughts/shared/research/2025-08-05_05-15-59_baml_test_assertions.md
├─ Problem Summary
├─ Relevant Files Identified
│  ├─ src/parser/validation.rs
│  ├─ src/runtime/test_runner.rs
│  └─ tests/assertions/*.baml
├─ Information Flow Analysis
│  └─ parse() → validate() → execute_test()
├─ Key Findings
│  ├─ Validation uses Zod schemas
│  └─ Current assertion syntax not checked
└─ Recommended Approach
```

#### Implementation Plan Example

```
thoughts/shared/plans/baml-test-assertion-validation-with-research.md
├─ Step 1: Add validation logic
│  ├─ File: src/parser/validation.rs
│  ├─ Function: validate_assertion_syntax()
│  └─ Tests: test_validation.rs
├─ Step 2: Update parser
│  ├─ File: src/parser/parser.rs
│  ├─ Integration point: parse_test_file()
│  └─ Error handling: ValidationError enum
└─ Step 3: Add integration tests
   ├─ File: tests/assertions/syntax_errors.baml
   └─ Expected behavior: reject invalid syntax
```

#### Progress Compaction Example

For complex implementations, `progress.md` tracks state across context resets:

```
thoughts/shared/progress/cancelation_feature_progress.md
├─ Goal: Add cancelation support to BAML runtime
├─ Completed Steps
│  ├─ [✓] Step 1: Add CancelToken struct
│  ├─ [✓] Step 2: Thread token through runtime
│  └─ [✓] Step 3: Add cancellation tests
├─ Current Step
│  └─ [→] Step 4: Integrate with async runtime
├─ Remaining Steps
│  ├─ [ ] Step 5: Add WASM bindings
│  └─ [ ] Step 6: Update documentation
└─ Current Issue
   └─ Async runtime integration needs tokio::select!
```

### Prompt Template Structure

The workflow is implemented through three prompt templates:

**Research Prompt Template** instructs agents to:
- Use subagents for file discovery and code analysis
- Identify information flow paths
- Compact findings into structured summaries
- Focus on correctness over completeness

**Plan Prompt Template** instructs agents to:
- Reference the research document for context
- Define numbered, sequential steps
- Specify exact file modifications
- Detail testing and verification procedures

**Implementation Prompt Template** instructs agents to:
- Execute plan steps in order
- Use git worktrees for isolation
- Run tests after each step
- Compact progress for complex tasks

### Human Review Checkpoints

**Review Checkpoint 1: Research Document**
- What to verify: Correct understanding of codebase structure, relevant files identified, accurate information flow analysis, no false assumptions
- Cost of error: 1000s of lines of incorrectly architected code

**Review Checkpoint 2: Implementation Plan**
- What to verify: Sound architectural approach, appropriate file modifications, complete testing strategy, alignment with codebase conventions
- Cost of error: 100s of lines of code in wrong locations or wrong patterns

**Review Checkpoint 3: Code Review (Optional)**
- What to verify: Mental alignment with team, understanding of what changed and why, tests validate expected behavior
- Cost of error: 1-10 lines of incorrect code

> The leverage ratio shows why reviewing ~400 lines of specs (200 research + 200 plan) provides more value than reviewing 2000 lines of generated code.

### Integration with Broader FIC Methodology

| FIC Principle | Workflow Implementation |
|--------------|------------------------|
| Context Quality = f(Correctness, Completeness, Size, Trajectory) | Research validates correctness; Plan ensures completeness; Phase boundaries control size; Sequential steps maintain trajectory |
| 40-60% Context Utilization | Each phase starts fresh at 10-15% utilization |
| Intentional Compaction | Phase transitions require explicit artifact creation |
| Human-in-the-Loop | Review gates at Research and Plan phases |
| Subagent Isolation | Research phase extensively uses subagents |

### Workflow Flexibility

While the standard workflow is Research → Plan → Implement, variations are common. The key principle is that each phase must produce a correctly-scoped, compacted artifact before proceeding.
