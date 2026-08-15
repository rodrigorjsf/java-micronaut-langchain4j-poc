---
name: agentic-codebase-audit
description: Inventory a codebase's agentic seams, score what each one actually enforces, and produce a ranked, capped improvement plan.
disable-model-invocation: true
---

# Auditing an agentic codebase

A **seam** is where the system meets something it does not control: the model
provider, a tool, retrieved text, a stored conversation, another agent. Agentic
systems fail at seams, and they fail mostly by **absence** — the thing that would
have caught it was never written, so no diff is wrong and there is no line to
point at. Four absences, none of which looks like a bug in any single file:

- a provider client constructed inside the request path — nothing is *wrong*, and
  the connection count now tracks traffic instead of capacity;
- guardrails on the way in and nothing on the way back out;
- a catalogue that loads eleven of its twelve entries and says nothing about the
  twelfth;
- a conversation key with no tenant component, one identifier bug away from
  cross-user bleed, with nothing in the type system objecting.

So this pass runs the opposite way round from a code review. Take a fixed list of
seams, ask at each one what job must be done there, then go find who does it.

## An empty result is a finding

Phrase every check as a query whose **small or empty answer is the defect**. Run
it, and record the output beside it.

```
BAD   "Check whether the skill catalogue fails fast."
GOOD  "Point one catalogue entry at a tool name that does not exist and boot.
       A successful boot is the finding."

BAD   "There is no timeout on the model call."
GOOD  "Searched the client package for a timeout setting: 0 hits. The SDK
       default applies — look it up and write the number down."
```

The GOOD form produces evidence you can paste; the BAD form produces an opinion,
and an opinion loses the argument with whoever wrote the code.

This audit names **checks, not rules**. Where a sibling skill owns a seam's
doctrine, the probe establishes the fact and the fix is handed over by name — run
`agentic-tool-boundary`, `progressive-tool-disclosure`, `prompt-injection-layers`,
`retrieval-that-earns-its-place`, `agentic-service-composition`,
`conversation-memory-and-compaction`, `subagent-context-isolation`,
`llm-cost-observability` or `reviewing-agent-tools-and-skills` once the finding is
written. Each seam block in `SEAM-PROBES.md` names its own owner in its first
line, so you never have to guess which one.

## The nine seams

Frameworks name everything differently, so locate by **shape**, never by keyword.

| Seam | Find it by shape | The absence that hides there |
|---|---|---|
| **Model access** | follow one inbound request to the first call on the provider SDK's client type, whatever it is called here; note where that client is *constructed*, not only called | built per request; no bound on the call |
| **Prompt assembly** | whatever composes the standing instructions before that call; finding two such places is already the finding | configuration or user data reaching the standing instructions |
| **Tool boundary** | the widest outbound fan-out in the repository: every function annotated, registered or otherwise published to a model | no record of what each tool may reach |
| **Discovery** | whatever assembles the tool and skill list handed to the model each turn; if nothing assembles it, the list is a constant somewhere — find it | a load failure that is not a boot failure |
| **Guardrails** | anything that reads text and can refuse — and the retriever, if there is one, whose chunks reach the prompt unread; note which side each check sits on | every one of them on the inbound side |
| **Memory** | the read path and the write path of the durable conversation store | nothing partitioning one caller from another |
| **Evals** | test sources that call a real model, usually tagged out of the default build | a suite no prompt change can fail |
| **Observability** | what one turn leaves behind: spans, metrics, log lines | a turn nobody can reconstruct afterwards |
| **Sub-agents** | a model call reachable from inside a tool, or from inside another agent's turn | no ceiling on depth or fan-out |

Evals and observability enforce nothing themselves. They carry rows because they
are how you find out that a control at another seam stopped working.

→ `SEAM-PROBES.md` — one block per seam, every probe written so that a small,
empty or successful answer is the finding. Open it at the start of Step 2; it is
the working part of this skill.

## Run it in this order

### Step 1 — Locate

Work the shape column, seam by seam. Output is nine rows, each naming a
`file:symbol` — or one of three non-locations that are never interchangeable:

- **`absent`** — the seam applies here and there is no code at it at all, so
  there is no symbol to name. Scores 0, and it is your loudest finding. It stays
  in the report: deleting the row is how a codebase with no evals gets a clean
  audit.
- **`n/a` plus a trigger** — the architecture has no such seam yet, so there is
  nothing to score. The trigger is the whole point: the first fan-out, the first
  uploaded document, the second tenant. `n/a` with no trigger decays into "we
  forgot" inside two quarters.
- **`not yet probed`** — you ran out of budget, and the summary says so.

A blank cell is a seam you forgot. The **Where** column is the only thing that
tells `n/a` from `not yet probed`; both score `—`, so never let a score stand in
for the distinction.

→ `OUTPUT-SHAPES.md` — the nine-row table, one finding block and a worked
ranking, all filled in. Open it before writing the first row; it is the shape of
everything this audit produces.

### Step 2 — Probe, and score what you find

| Score | State | What you may claim |
|---|---|---|
| 0 | missing | nothing does this job — the evidence is the empty query |
| 1 | local | it holds where it was written, and a new path can skip it |
| 2 | structural | a new path gets it without asking |
| 3 | proved | a named test goes red when the control is removed |

**The unit is the job, not the seam. A seam is a set of jobs, and the seam takes
its lowest job's score — never an average, never its best job's.** A seam's jobs
are its **scored probes** in `SEAM-PROBES.md`; probes marked *fact-only* establish
a count, a cost or a byte offset and carry no score, so two auditors working the
same repository arrive at the same job list and their tables compare. Say which
job set the score, in the evidence.

**A job nothing does anywhere is 0, even where the seam has code and the Where
column names a real `file:symbol`.** Model access holding one shared client, one
model identifier and a boot check still scores 0 when nothing in the codebase
*chose* a bound on the call: the bound is a job, and it is at 0. This is the rung
auditors inflate, because the seam looks built.

**Within one job, the score is that job's weakest path, never its best.** Eleven
tools whose failures are red-tested and a twelfth returning raw exceptions is a 1,
because the score answers what the job *guarantees*, and it guarantees whatever
the worst path does. Without the two rules together the row gets lifted by the
strongest control on it and two audits of the same codebase stop comparing.

`absent` in **Where** and 0 in **Score** are different claims. `absent` means the
seam has no code at all, so *every* job at it is at 0 and there is no probe number
to cite — that is the one row whose evidence is an empty search rather than a
probe output. A 0 anywhere else means one job among several has nothing doing it.
Every `absent` scores 0, and most 0s are not `absent`.

Both rungs above 0 are measured rather than read for, in ladder order:

**1 vs 2 — add a call site the way a newcomer would.** Copy the nearest existing
example, wire it the minimal way, change nothing else, run it, and write down
whether the control fired without you naming it. If you had to remember to call
something, it is 1. This is the rung nearly every *job that exists at all* lands
on, and reading the code decides it wrong every time: the correct call sites are
what you see, and the one you are scoring has not been written yet.

**2 vs 3 — delete the control locally and run the suite.** If nothing goes red
the score is 2, whatever the code looks like. Restore it, and record which test
you expected to fail. At the **evals** seam the control *is* the suite, so
deleting it to see a test go red is circular; §7 of `SEAM-PROBES.md` carries that
seam's four rungs, measured against the gate rather than against a test.

So 2 is not the finish line either. A seam at 2 decays to 1 the day it grows a
second door — a raw client kept beside the wrapper, a second registration route
for the one tool that did not fit the first. Nobody deletes anything, so the
change reads as an addition in review and as a demotion only in a diff against
your last table.

Several probes, and both of these measurements, change the system to watch what
it does: breaking a configuration, deleting a control, adding a call site. Run
them on a scratch branch and revert. "Report first, fix second" governs findings,
not probes — a probe that mutates and reverts is measurement. Skip them and you
report those seams as healthy when they are not, because the code looks correct
and only executing it disagrees.

### Step 3 — Answer the ten coverage items

Output is ten lines, one per item, each carrying four fields: the item, the seam
holding its control, that seam's score, and the verdict — **covered**,
**uncovered**, or **n/a** plus the trigger that ends it. An item with a blank
verdict is not answered, and a verdict with no score behind it is an opinion.
Where an item has a second seam, name it on the same line.

Run this only once every seam carries a score. The map reads scores, so running
it earlier produces guesses.

→ `OWASP-COVERAGE.md` — the ten items of the OWASP Top 10 for Agentic
Applications 2026 mapped to seams, the rule that turns a score into one of the
three verdicts, the second seam several items also lose ground at, and the
triggers for the three that do not apply to a single-agent application.

### Step 4 — Blast radius

Severity labels do not rank. Two axes do, both answerable from probe output.

**Reach** — can something an attacker controls get to this seam: their text, or
an identifier they supply? Uploaded documents, retrieved pages, tool responses
and sub-agent replies are attacker-writable in some deployment; name which.

**Reversibility** — of the **incident the gap permits**, not of the gap itself. A
missing error taxonomy is not "irreversible"; the wasted retries it causes are
reversible, and the cross-tenant read a missing partition key permits is not.
Classify the incident, or two auditors score the same finding differently.

| | Irreversible incident | Reversible incident |
|---|---|---|
| **Attacker-reachable** | rank 1 | rank 2 |
| **Internal only** | rank 3 | rank 4 |

Inside a rank, in this order: **silent before loud**, then every-request before
rare-path, then cheapest fix first. Silence is the tie-break that matters — a
seam that fails loudly gets fixed by whoever is on call; a seam that fails
silently is still failing a year later, and the first person to notice is a
customer.

### Step 5 — The plan

Every finding names its seam, its score and target, the probe output it came
from, its two rank inputs and whether it fails silently, what it costs if it is
left, the **smallest change** that closes it, the test that **proves** it closed,
and the coverage items it moves.

**Smallest change is a constraint, not a courtesy.** An audit that recommends a
rewrite gets filed, and the seam stays at 0 for another year. Size it in files
touched, and split any finding whose fix is bigger than one change into the part
that stops the bleeding and the part that does it properly.

**Cap the plan at ten findings.** Ten that get done beat thirty that get read.
The rest stay in the scored table, where the next audit will find them.

## Report first, fix second

Land the whole report before changing a line. An audit that stops to fix its
second finding never reaches its ninth seam — and the ninth is exactly where
absence hides, because it is the seam nobody built and therefore nobody
remembers.

## When you have two hours, not two days

A partial audit is a deliverable; an all-or-nothing procedure on a large codebase
produces nothing at all. Probe these four first — they need no running system,
and they are where absence is both most common and most expensive:

1. **Guardrails, probe 5.1** — the census of every path carrying text the user
   did not type, and which of them has a check.
2. **Memory, probe 6.2** — the key builder and what partitions one caller from
   another.
3. **Tool boundary, probes 3.3 and 3.4** — the destination-parameter list, and
   the framework's default error handler read verbatim.
4. **Model access, probes 1.1 and 1.4** — the construction sites, and the bound
   this codebase chose on the call.

Mark the other five `not yet probed`, name them in the summary, and stop.

## The artefact, and when you run it again

The deliverable is one committed file at a **stable path** — the nine-row scored
table at its head, the capped plan under it, same path every run. Stable and
committed, because an audit compounds only if the next one can `diff` against the
last: a seam that went from 2 to 1 is the cheapest finding you will ever get, and
nothing but the previous table can show it to you. A report pasted into a ticket
is a one-off opinion.

**Stamp the table with the tree it was run against** — date, branch, commit — and
with the stamp of the run before it. A 2 that has become a 1 is only a finding if
you can tell it from an audit of a different tree; without the stamp the diff
shows two tables and no way to know whether the code moved or the auditor did.
`OUTPUT-SHAPES.md` carries the stamp line in the table head.

Re-run on a trigger, not when someone remembers: a tool is added or an existing
tool's authority widens; a framework or provider SDK major version; a new model
or a new provider; the first agent-to-agent hop, or the first unattended run.

## Done when

- every seam reads as a `file:symbol` with a score of 0–3, or `absent` with 0, or
  `n/a` plus its trigger, or `not yet probed` — nine rows, no blank cell;
- every score is the **lowest scored probe** at that seam, and the evidence names
  which probe set it;
- every 2 was earned by adding a call site the naive way and watching the control
  cover it unprompted; every 3 by deleting the control and watching a named test
  go red — at **evals**, by making the gate non-blocking and watching the merge be
  refused;
- every finding quotes the probe output it came from, not a judgement about the
  code;
- every finding names a change sized in files touched and the test that closes
  it;
- all ten coverage items carry a seam, that seam's score and a verdict;
- the table head carries this run's date, branch and commit and the previous
  run's, and the table and capped plan are **committed** at the path the next run
  will diff against.

A seam at 3 is proved by a test, not by production behaviour. Whether the control
is tuned *right* — a threshold, a budget, a refusal rate — is the evals seam's
question, answered with a dataset rather than with this audit.

Any threshold you did not measure on this codebase is illustrative, and the
report says so. A measured-looking number nobody measured is the one thing that
gets an otherwise correct plan thrown out.
