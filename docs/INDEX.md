# The book

A working agentic chat backend, and the reasoning behind every part of it.

This is a study project. The code runs, the numbers are measured on one real
machine, and the parts that were wrong the first time say so — the interesting
material is usually in what a measurement overturned, not in what it confirmed.

## Reading order

| # | Chapter | What it answers |
|---|---|---|
| — | [`README.md`](../README.md) | What this is and how to run it |
| 1 | [Anatomy of a turn](01-request-path.md) | What happens between an HTTP request and a reply, and what each step costs |
| 2 | [Context engineering, applied](02-context-engineering.md) | How 50+ tools, a growing conversation and a cache budget coexist |
| 3 | [Security](03-security.md) | The OWASP Agentic Top 10, mapped to the class that answers each item |
| 4 | [Operations](04-operations.md) | Running it, the local AWS emulator, metrics, cost |
| 5 | [Evaluation](05-evaluation.md) | How a prompt change is proved not to have broken anything |

## Two other places to look

**[`CONTEXT.md`](../CONTEXT.md)** — the ubiquitous language. What a *turn*, a
*verdict*, a *skill*, an *activation* precisely mean here, which package owns
what, and the seven invariants the tests enforce. Read it before changing
anything; a term used loosely is how a boundary erodes.

**[`docs/adr/`](adr/README.md)** — one record per decision that was hard to
reverse, surprising, or paid for with a measurement. Records marked
**[measured]** quote command output produced on the development machine.

## The decisions worth reading first

Three ADRs carry findings that changed the design rather than confirming it:

- [0006 — the triage judge](adr/0006-llm-as-judge-triage.md): the newer model was
  **6.5× slower** than the older one on the same prompt, and one provider silently
  ignores a parameter the previous generation honoured.
- [0010 — retrieval](adr/0010-rag-over-the-assistants-own-documentation.md): the
  similarity-score distributions of relevant and irrelevant questions **overlap**,
  so the threshold-only design that looked simpler could not work.
- [0004 — floci](adr/0004-floci-as-the-aws-and-cache-substrate.md): two traps in
  the local AWS emulator, each of which produces a failure that looks like a
  different problem entirely.

## Reusable pieces

[`.claude/skills/`](../.claude/skills/) holds six skills written to be
project-agnostic — the tool boundary, progressive disclosure, the triage gate,
injection defence, retrieval, and cost observability. They carry the numbers from
this project but none of its code, so they travel to any agentic backend.

[`.claude/rules/micronaut-langchain4j.md`](../.claude/rules/micronaut-langchain4j.md)
holds what does not travel: the framework traps that compiled, or ran, or passed a
test while being wrong.
