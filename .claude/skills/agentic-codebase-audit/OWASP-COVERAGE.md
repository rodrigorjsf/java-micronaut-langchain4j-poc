# Coverage lens — OWASP Top 10 for Agentic Applications 2026

## What is confirmed, and what is not

The ten identifiers `ASI01`–`ASI10` and the ten topics they name are confirmed:
the OWASP GenAI Security Project published the *OWASP Top 10 for Agentic
Applications 2026* on 9 December 2025, and independent secondary summaries agree
on the set.

The exact **titles** do not agree between sources. ASI02 appears as both "Tool
Misuse" and "Tool Misuse & Exploitation"; ASI03 as "Identity & Privilege Abuse"
and "Agent Identity & Privilege Abuse"; ASI04 as "Agentic Supply Chain
Vulnerabilities" and "Agentic Supply Chain Compromise"; ASI05 sometimes carries
"(RCE)"; ASI08 as "Cascading Failures" and "Cascading Agent Failures". Treat the
identifiers below as exact and the wording as approximate — check a title against
the published document before quoting it in anything anyone signs.

**The seam mapping, the "covered when" column and the triggers are this skill's
own, not OWASP's.** The published document maps its items to OWASP's own threats
and mitigations taxonomy, which is a different exercise from this one.

## The map

| Item | Seam holding the control | Covered when |
|---|---|---|
| **ASI01 Agent Goal Hijack** | guardrails, input side | every path carrying text the user did not type is checked before it reaches the next prompt |
| **ASI02 Tool Misuse & Exploitation** | tool boundary | no parameter names a destination, arguments are validated before the call, and each tool carries its own authority rather than the union of all of them |
| **ASI03 Identity & Privilege Abuse** | tool boundary | a call runs with the caller's authority rather than one shared service identity, so it cannot reach data the caller could not |
| **ASI04 Agentic Supply Chain Vulnerabilities** | discovery | every tool, server and skill definition the model can reach is pinned and resolved at startup, so a changed definition cannot take effect silently |
| **ASI05 Unexpected Code Execution** | tool boundary | no tool accepts code, a shell command, a query language or a template; where one must, it runs somewhere its blast radius ends |
| **ASI06 Memory & Context Poisoning** | memory | code decides what enters durable memory, not whatever text arrived |
| **ASI07 Insecure Inter-Agent Communication** | sub-agents | a message from another agent is authenticated, and its content is handled as data |
| **ASI08 Cascading Failures** | sub-agents | a hop budget, a per-run step and cost ceiling, and a breaker that opens instead of retrying into a failing dependency |
| **ASI09 Human-Agent Trust Exploitation** | guardrails, output side | truncation is announced, an action with a consequence is confirmed against what will happen, and a refusal is distinguishable from an answer |
| **ASI10 Rogue Agents** | observability | every run is attributable to a trigger and a principal, and a run that starts behaving differently is visible in telemetry rather than in a support ticket |

Read it in **one direction only**: for each item, name the seam and read the
score off the table. *"ASI06 is uncovered because the memory seam scored 0"* is
the sentence this audit exists to produce.

## Three cautions while you fill it in

**A score of 1 is uncovered.** One call site holds the control and the next one
added will not — that is a gap with a date on it rather than a gap with a
location, and it reads as covered to anyone skimming.

**An absent seam covers nothing silently.** A codebase with no sub-agents leaves
ASI07 *not applicable*, never *covered*. Write `n/a — no sub-agents` plus its
trigger, so the next auditor reads a decision instead of a blank.

**One item, one seam is a simplification.** The table gives each item its primary
seam; several have a second:

- **ASI01** also loses ground at *prompt assembly*, when the standing
  instructions are built from configuration someone can edit;
- **ASI03** also loses ground at *memory*, where a key with no tenant component
  is a privilege boundary that does not exist;
- **ASI08** is bounded by the retry budget at *model access* as much as by any
  depth cap;
- **ASI10** rests on *evals*, which is what a behavioural baseline is made of.

Score the mapped seam, then name the second seam in the finding.

## Triggers for the conditional items

Three items have no seam at all in a single-agent, request-scoped application.
Reporting them as unprotected is noise; reporting them `n/a` without a trigger is
how they are missed on the day they start applying.

| Item | Applicable from |
|---|---|
| **ASI07** | the first time a second agent is addressed over a wire |
| **ASI08** | the first fan-out, or the first retry loop that can re-enter a model call |
| **ASI10** | the first agent that runs unattended — on a schedule, on a webhook, or holding standing credentials |

**ASI04** applies to every codebase, and sharpens the moment a tool or server
definition is fetched from somewhere you do not control.

Answer all ten. `n/a` is a valid answer exactly once its trigger is written
beside it.
