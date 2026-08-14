# Spec-Driven Development: Exploring Three SDD Tools (Martin Fowler)

**Source:** https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html
**Category:** Spec-Driven Development
**Platform:** martinfowler.com — "Exploring Gen AI" series

## Summary

A critical, hands-on analysis of three SDD tools (Kiro, GitHub Spec Kit, Tessl) by a practitioner from the Martin Fowler team. Goes beyond the hype to examine the definition of "spec" in this context, the distinction between spec levels (spec-first vs spec-anchored vs spec-as-source), and raises open questions about review overhead, AI non-determinism, one-size-fits-all workflows, and who the actual target user is.

## Content

I've been trying to understand one of the latest AI coding buzzwords: Spec-driven development (SDD). I looked at three of the tools that label themselves as SDD tools and tried to untangle what it means, as of now.

## Definition

Like with many emerging terms in this fast-paced space, the definition of "spec-driven development" (SDD) is still in flux. Here's what I can gather from how I have seen it used so far: Spec-driven development means writing a "spec" before writing code with AI ("documentation first"). The spec becomes the source of truth for the human and the AI.

[GitHub](https://github.com/github/spec-kit/blob/main/spec-driven.md): "In this new world, _maintaining software means evolving specifications_. […] The lingua franca of development moves to a higher level, and code is the last-mile approach."

[Tessl](https://docs.tessl.io/introduction-to-tessl/concepts): "A development approach where _specs — not code — are the primary artifact_. Specs describe intent in structured, testable language, and agents generate code to match them."

After looking over the usages of the term, and some of the tools that claim to be implementing SDD, it seems to me that in reality, there are multiple implementation levels to it:

1. **Spec-first**: A well thought-out spec is written first, and then used in the AI-assisted development workflow for the task at hand.
2. **Spec-anchored**: The spec is kept even after the task is complete, to continue using it for evolution and maintenance of the respective feature.
3. **Spec-as-source**: The spec is the main source file over time, and only the spec is edited by the human, the human never touches the code.

All SDD approaches and definitions I've found are spec-first, but not all strive to be spec-anchored or spec-as-source. And often it's left vague or totally open what the spec maintenance strategy over time is meant to be.

## What is a spec?

The key question in terms of definitions is: What is a spec? There doesn't seem to be a general definition. The closest I've seen to a consistent definition is the comparison of a spec to a "Product Requirements Document."

The term is quite overloaded at the moment. Here is my attempt at defining what a spec is:

> A spec is a structured, behavior-oriented artifact - or a set of related artifacts - written in natural language that expresses software functionality and serves as guidance to AI coding agents. Each variant of spec-driven development defines their approach to a spec's structure, level of detail, and how these artifacts are organized within a project.

There is a useful difference to be made between specs and the more general context documents for a codebase. That general context are things like rules files, or high level descriptions of the product and the codebase. Some tools call this context a **memory bank**—these files are relevant across all AI coding sessions in the codebase, whereas specs are only relevant to the tasks that actually create or change that particular functionality.

## The challenge with evaluating SDD tools

It turns out to be quite time-consuming to evaluate SDD tools and approaches in a way that gets close to real usage. You would have to try them out with different sizes of problems, greenfield, brownfield, and really take the time to review and revise the intermediate artifacts with more than just a cursory glance. Because as GitHub's blog post about spec-kit says: "Crucially, your role isn't just to steer. It's to verify. At each phase, you reflect and refine."

For two of the three tools I tried it also seems to be even more work to introduce them into an existing codebase, therefore making it even harder to evaluate their usefulness for brownfield codebases.

---

## Kiro

[Kiro](https://kiro.dev/) is the simplest (or most lightweight) one of the three. It seems to be mostly spec-first—all the examples I have found use it for a task, or a user story, with no mention of how to use the requirements document in a spec-anchored way over time.

**Workflow:** Requirements → Design → Tasks

Each workflow step is represented by one markdown document, and Kiro guides you through those 3 workflow steps inside of its VS Code-based distribution.

- **Requirements:** Structured as a list of requirements, where each requirement represents a "User Story" (in "As a…" format) with acceptance criteria (in "GIVEN… WHEN… THEN…" format)
- **Design:** Consists of sections like component architecture diagram, Data Flow, Data Models, Error Handling, Testing Strategy, Implementation Approach, Migration Strategy
- **Tasks:** A list of tasks that trace back to the requirement numbers, with UI elements to run tasks one by one and review changes per task

Kiro also has the concept of a memory bank, they call it "steering." The default topology created by Kiro when you ask it to generate steering documents is: `product.md`, `structure.md`, `tech.md`.

---

## Spec-kit

[Spec-kit](https://github.com/github/spec-kit) is GitHub's version of SDD. It is distributed as a CLI that can create workspace setups for a wide range of common coding assistants. You interact with spec-kit via slash commands in your coding assistant. Because all of its artifacts are put right into your workspace, this is the most customizable one of the three tools.

**Workflow:** Constitution → 𝄆 Specify → Plan → Tasks 𝄇

Spec-kit's memory bank concept is a prerequisite for the spec-driven approach. They call it a **constitution**. The constitution is supposed to contain the high level principles that are "immutable" and should always be applied to every change. It's basically a very powerful rules file that is heavily used by the workflow.

In each of the workflow steps (specify, plan, tasks), spec-kit instantiates a set of files and prompts with the help of a bash script and some templates. The workflow makes heavy use of checklists inside of the files to track necessary user clarifications, constitution violations, research tasks, etc.—like a "definition of done" for each workflow step.

One spec is made up of many files: `data-model`, `plan`, `tasks`, `spec`, `research`, `api`, `component` (potentially 8+ files per feature).

At first glance, GitHub seems to be aspiring to a spec-anchored approach ("That's why we're rethinking specifications — not as static documents, but as living, executable artifacts that evolve with the project"). However, spec-kit creates a branch for every spec that gets created, which seems to indicate they see a spec as a living artifact for the lifetime of a change request, not the lifetime of a feature.

---

## Tessl Framework

_(Still in private beta)_

Like spec-kit, the [Tessl Framework](https://docs.tessl.io/introduction-to-tessl/quick-start-guide-tessl-framework) is distributed as a CLI. The CLI command also doubles as an MCP server.

Tessl is the only one of these three tools that explicitly aspires to a spec-anchored approach, and is even exploring the spec-as-source level of SDD. A Tessl spec can serve as the main artifact that is being maintained and edited, with the code even marked with a comment at the top saying `// GENERATED FROM SPEC - DO NOT EDIT`. This is currently a 1:1 mapping between spec and code files.

Here is an example workflow:
- `tessl document --code ...js` — reverse-engineer a spec from an existing JavaScript file
- `tessl build` — generate the corresponding JavaScript code file from the spec

Tags like `@generate` or `@test` seem to tell Tessl what to generate. The API section shows the idea of defining at least the interfaces that get exposed to other parts of the codebase in the spec.

Putting the specs for spec-as-source at a quite low abstraction level, per code file, probably reduces the number of steps and interpretations the LLM has to do, and therefore the chance of errors. Even at this low abstraction level, the author saw non-determinism in action when generating code multiple times from the same spec—iterating on the spec to make it more specific increased the repeatability of code generation.

---

## Observations and Questions

These three tools are all labelling themselves as implementations of spec-driven development, but they are quite different from each other. So the first thing to keep in mind when talking about SDD is: **it is not just one thing**.

### One workflow to fit all sizes?

Kiro and spec-kit provide one opinionated workflow each, but neither seems suitable for the majority of real life coding problems. When asked to fix a small bug, Kiro turned it into 4 "user stories" with a total of 16 acceptance criteria, including: *"As a developer, I want the transformation function to handle edge cases gracefully, so that the system remains robust when new category formats are introduced."*

With spec-kit, a feature that would be a 3-5 point story on a real team generated so many markdown files to review that it felt like overkill. The conclusion: in the same time it took to run and review the spec-kit results, the feature could have been implemented with "plain" AI-assisted coding, with much more sense of control.

**An effective SDD tool would have to provide flexibility for different sizes and types of changes.**

### Reviewing markdown over reviewing code?

Spec-kit created a LOT of markdown files to review—repetitive with each other, and with existing code. Some contained code already. Overall, they were verbose and tedious to review. In Kiro it was a little easier (only 3 files), but Kiro was way too verbose for a small bug.

**To be honest: reviewing code directly is often preferable to reviewing all these markdown files.**

### False sense of control?

Even with all of these files and templates and prompts and checklists, agents frequently did not follow all the instructions. For example: Spec-kit did a lot of research on the existing code, but ultimately the agent ignored the notes that these were descriptions of existing classes—it took them as a new specification and generated them all over again, creating duplicates.

The past has shown that the best way to stay in control is small, iterative steps. Lots of up-front spec design is questionable, especially when overly verbose. **Small work packages almost seem counter to the idea of SDD.**

### How to effectively separate functional from technical spec?

It is a common idea in SDD to be intentional about the separation between functional spec and technical implementation. In practice, using spec-kit, there was frequent confusion about when to stay on the functional level and when to add technical details. When you think back on the many user stories in software engineering history that weren't properly separating requirements from implementation, we don't have a good track record as a profession to do this well.

### Summary Assessment

| Tool | Spec Level | Complexity | Best For |
|------|-----------|------------|----------|
| Kiro | Spec-first | Low | Simple, well-defined tasks |
| Spec-kit | Spec-first (aspiring anchored) | High | Larger greenfield features |
| Tessl | Spec-anchored / Spec-as-source | Medium-High | Teams committed to spec-driven long-term |

Until usage reports emerge from people using these tools for a period of time on a "real" codebase, there are still a lot of open questions about how SDD works in real life.
