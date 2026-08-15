# Coverage lens — OWASP Top 10 for Agentic Applications 2026

## Where the ten come from

The OWASP GenAI Security Project published the *OWASP Top 10 for Agentic
Applications*, version 2026, on **9 December 2025**:
<https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/>.
The ten identifiers **ASI01–ASI10 and their order are confirmed**: two
independent secondary sources — the Modulos governance guide and the DeepTeam
framework reference, both read on 15 August 2026 — enumerate the same ten items
in the same order, and the resource page above carries the date.

**Key your table on the identifier, never on the title.** Those same two sources
disagree on the wording of four of the ten:

| Item | One source has | The other has |
|---|---|---|
| **ASI02** | Tool Misuse | Tool Misuse & Exploitation |
| **ASI03** | Identity & Privilege Abuse | Agent Identity & Privilege Abuse |
| **ASI04** | Agentic Supply Chain Vulnerabilities | Agentic Supply Chain Compromise |
| **ASI08** | Cascading Failures | Cascading Agent Failures |

That is the observed disagreement, not the whole of it: the primary document is
a gated download that was **not read here**, so each title used below is
whichever of the two wordings above reads more plainly — unverified against the
document, and the other six may differ in sources nobody checked. Quote the
identifier and describe the item in your own words. A reader holding the
document will have different words for the same thing, and an audit that argues
about a title has stopped auditing.

**The seam mapping, the "covered when" column and the triggers are this skill's
own, not OWASP's.** The published document maps its items to OWASP's own threats
and mitigations taxonomy, which is a different exercise from this one.

## The map

| Item | Seam holding the control | Covered when — and the probe that measures it |
|---|---|---|
| **ASI01 Agent Goal Hijack** | guardrails, input side | every path carrying text the user did not type is checked before it reaches the next prompt (**5.1**), and a check that cannot answer produces a chosen, recorded outcome rather than an unlogged pass (**5.3**) |
| **ASI02 Tool Misuse & Exploitation** | tool boundary | no parameter names a destination (**3.3**), a forbidden argument is rejected at the boundary rather than by the downstream system (**3.8**), and each tool carries its own authority rather than the union of all of them (**3.6**) |
| **ASI03 Identity & Privilege Abuse** | tool boundary | a call runs with the caller's authority rather than one shared service identity, so it cannot reach data the caller could not (**3.6**) |
| **ASI04 Agentic Supply Chain Vulnerabilities** | discovery | every tool, server and skill definition the model can reach resolves at startup (**4.1**) and is pinned to a version or a digest (**4.4**), so a changed definition cannot take effect silently |
| **ASI05 Unexpected Code Execution** | tool boundary | no tool accepts code, a shell command, a query language or a template; where one must, it runs somewhere its blast radius ends (both halves, **3.3**) |
| **ASI06 Memory & Context Poisoning** | memory | code decides what enters durable memory, not whatever text arrived (**6.3**) |
| **ASI07 Insecure Inter-Agent Communication** | sub-agents | a message from another agent is authenticated, and its content is handled as data (**9.4**) |
| **ASI08 Cascading Failures** | sub-agents | a hop budget and a per-run step and spend ceiling (**9.1**), and a retry budget with something that opens instead of retrying into a failing dependency (**1.4**) |
| **ASI09 Human-Agent Trust Exploitation** | guardrails, output side | truncation is announced, an action with a consequence is confirmed against what will happen, and a refusal is distinguishable from an answer (**5.2 a–c**) |
| **ASI10 Rogue Agents** | observability | every run is attributable to a trigger and a principal (**8.6**), and a run that starts behaving differently is visible in telemetry (**8.3**) rather than in a support ticket |

Every clause carries the probe that measures it, and that is a constraint on this
table rather than a convenience: **a clause with no probe number behind it is a
verdict the audit cannot support.** If you add a condition here, add its probe to
`SEAM-PROBES.md` first — otherwise Step 3 reads a verdict off a score that never
measured the thing the row claims.

Read it in **one direction only**: for each item, name the seam, read the score
off the table, and turn the score into a verdict — **uncovered** at 0 or 1,
**covered** at 2 or 3, **n/a** plus a trigger where the seam does not exist yet,
**unmeasured** where the seam carries no score at all because a time-boxed run
stopped short of measuring it.
*"ASI06 is uncovered because the memory seam scored 0"* is the sentence this
audit exists to produce.

## Three cautions while you fill it in

**A score of 1 is uncovered.** One call site holds the control and the next one
added will not — that is a gap with a date on it rather than a gap with a
location, and it reads as covered to anyone skimming.

**`unmeasured` is not `n/a`, and a 0 is never `unmeasured`.** `n/a` is a Step 1
locate result — the architecture has no such seam, so there is nothing to measure
and the item is closed until its trigger fires; a seam located `n/a` reads `n/a`
however short the run was. `unmeasured` says the seam exists and this run did not
reach it, so the item stays open and the next run owes it an answer. And a seam
that scored 0 off some of its probes is **uncovered**, not `unmeasured`: the
probes nobody ran can only confirm a 0, never lift it.

**`absent` and `n/a` answer differently.** A seam that applies and does nothing —
`absent`, score 0 — leaves its items **uncovered**, and they belong in the plan. A
seam the architecture does not have yet is `n/a`: a codebase with no sub-agents
leaves ASI07 *not applicable*, never *covered*. Write `n/a — no sub-agents` plus
its trigger, so the next auditor reads a decision instead of a blank.

**One item, one seam is a simplification.** The table gives each item its primary
seam; several have a second:

- **ASI01** also loses ground at *prompt assembly*, when the standing
  instructions are built from configuration someone can edit (**2.2**);
- **ASI03** also loses ground at *memory*, where a key with no tenant component
  is a privilege boundary that does not exist (**6.2**);
- **ASI08** is bounded at *model access* by the retry budget, and by whatever
  opens the circuit instead of retrying into a dependency that is already
  failing (**1.4**), as much as by any depth cap;
- **ASI10** rests on *evals*, which is what a behavioural baseline is made of —
  scored as one job against that seam's own ladder (**§7**).

A second seam carries a probe number for the same reason the table does: it will
be named in a finding, and a finding names the output it came from.

Score the mapped seam, then name the second seam in the finding. **Where the
primary seam is `n/a` and the second one is not, the verdict comes from the
second.** A single-agent application has no sub-agents and therefore no depth to
cap — but if it retries a model call with nothing bounding the retries, ASI08 is
uncovered, not `n/a`. Read the trigger below and you will see why: it fires on
*either* a fan-out *or* a retry loop.

## Triggers for the conditional items

Three items have no seam at all in a single-agent, request-scoped application.
Reporting them as unprotected is noise; reporting them `n/a` without a trigger is
how they are missed on the day they start applying.

| Item | Applicable from |
|---|---|
| **ASI07** | the first time a second agent is addressed over a wire — probe 9.4 |
| **ASI08** | the first fan-out, or the first retry loop that can re-enter a model call — probes 9.1 and 1.4 |
| **ASI10** | the first agent that runs unattended — on a schedule, on a webhook, on a queue, or holding standing credentials — probe 8.6 |

Each trigger names the probe that answers it, because "no agent runs unattended
here" is a claim about the codebase and not an impression of it. Probe 8.6 lists
every way a turn can start; if that list is one inbound request with a person
waiting on it, ASI10 is `n/a` **and you have the evidence**. Writing `n/a` from
memory is how the nightly job nobody mentioned goes unaudited.

**ASI04** applies to every codebase, and sharpens the moment a tool or server
definition is fetched from somewhere you do not control.

Answer all ten. `n/a` is a valid answer exactly once its trigger is written
beside it, and `unmeasured` exactly once the seam it names carries no score in
the table above it — an item left off the block is the one answer that is never
valid, whatever the budget was.
