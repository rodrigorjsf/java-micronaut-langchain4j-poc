# Coverage lens — OWASP Top 10 for Agentic Applications 2026

## Where the ten come from

The OWASP GenAI Security Project published the *OWASP Top 10 for Agentic
Applications*, version 2026, on **9 December 2025**, under its Agentic Security
Initiative: <https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/>.
The identifiers and titles in the map below are that document's own, taken from
its contents page and its contributor list (fetched 14 August 2026). Inside the
document ASI02 and ASI03 appear with both "and" and "&" — that is typography, not
a second name.

Secondary summaries retitle several items — "Agentic Supply Chain Compromise",
"Cascading Agent Failures", "Agent Identity & Privilege Abuse". Those are the
summarisers' words, not OWASP's; quote the map.

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
| **ASI05 Unexpected Code Execution (RCE)** | tool boundary | no tool accepts code, a shell command, a query language or a template; where one must, it runs somewhere its blast radius ends |
| **ASI06 Memory & Context Poisoning** | memory | code decides what enters durable memory, not whatever text arrived |
| **ASI07 Insecure Inter-Agent Communication** | sub-agents | a message from another agent is authenticated, and its content is handled as data |
| **ASI08 Cascading Failures** | sub-agents | a hop budget, a per-run step and cost ceiling, and a breaker that opens instead of retrying into a failing dependency |
| **ASI09 Human-Agent Trust Exploitation** | guardrails, output side | truncation is announced, an action with a consequence is confirmed against what will happen, and a refusal is distinguishable from an answer |
| **ASI10 Rogue Agents** | observability | every run is attributable to a trigger and a principal, and a run that starts behaving differently is visible in telemetry rather than in a support ticket |

Read it in **one direction only**: for each item, name the seam, read the score
off the table, and turn the score into a verdict — **uncovered** at 0 or 1,
**covered** at 2 or 3, **n/a** plus a trigger where the seam does not exist yet.
*"ASI06 is uncovered because the memory seam scored 0"* is the sentence this
audit exists to produce.

## Three cautions while you fill it in

**A score of 1 is uncovered.** One call site holds the control and the next one
added will not — that is a gap with a date on it rather than a gap with a
location, and it reads as covered to anyone skimming.

**`absent` and `n/a` answer differently.** A seam that applies and does nothing —
`absent`, score 0 — leaves its items **uncovered**, and they belong in the plan. A
seam the architecture does not have yet is `n/a`: a codebase with no sub-agents
leaves ASI07 *not applicable*, never *covered*. Write `n/a — no sub-agents` plus
its trigger, so the next auditor reads a decision instead of a blank.

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
