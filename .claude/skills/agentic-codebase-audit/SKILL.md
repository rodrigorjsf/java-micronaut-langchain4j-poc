---
name: agentic-codebase-audit
description: Run when you inherit, harden or are asked to assess an agentic backend and need its seams inventoried, what each one actually enforces scored, and a ranked, capped improvement plan.
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

| You are here because | Start at |
|---|---|
| this is the first audit of this codebase | *An empty result is a finding*, then *Run it in this order* |
| an earlier audit exists | Step 1 — it opens the previous table first — and [`ARTEFACT.md`](ARTEFACT.md) |
| you are probing a seam | [`SEAM-PROBES.md`](SEAM-PROBES.md), then [`SCORING.md`](SCORING.md) before the first score |
| every seam carries a score and the coverage lines are next | Step 3 and [`OWASP-COVERAGE.md`](OWASP-COVERAGE.md) |
| you are ranking findings and writing the plan | [`RANKING-AND-PLAN.md`](RANKING-AND-PLAN.md) |
| you are writing any of the three blocks | [`OUTPUT-SHAPES.md`](OUTPUT-SHAPES.md) |
| the budget will not cover every probe | [`TIME-BOXED-RUN.md`](TIME-BOXED-RUN.md) |

## An empty result is a finding

Phrase every check as a query whose **small or empty answer is the defect**. Two
habits, one per pair.

**Write the check as something another person could run.**

```
BAD   "Check whether the skill catalogue fails fast."
GOOD  "Point one catalogue entry at a tool name that does not exist and boot.
       A successful boot is the finding."
```

**Write down the output, never what you concluded from it.**

```
BAD   "There is no timeout on the model call."
GOOD  "Searched the client package for a timeout setting: 0 hits. The SDK
       default applies — look it up and write the number down."
```

The GOOD form produces evidence you can paste; the BAD form produces an opinion,
and an opinion loses the argument with whoever wrote the code.

This audit names **checks, not rules**. Where a sibling skill owns a seam's
doctrine, the probe establishes what this codebase chose and the fix is handed
over by name once the finding is written — the audit never argues the doctrine
itself. Each seam block in `SEAM-PROBES.md` opens by naming its owner, so you
never have to guess which one.

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

**Open the previous audit before you open the codebase**, at
`docs/agentic-audit.md` — the path this skill writes to, and the only place a
regression can come from. Nothing there means this is run 1: every **Previous**
field reads `first run`. A one-line pointer to another path instead of a table
means the last run committed elsewhere: follow it **once**, and if what you land
on is another pointer, stop and file that as a finding against the run that wrote
it. This is the whole of run 2's search — it opens the default path and does not
go looking.

Then work the shape column, seam by seam. Output is nine rows, each naming a
`file:symbol` — or one of three non-locations that are never interchangeable:

- **`absent`** — the seam applies here and there is no code at it at all, so
  there is no symbol to name. Scores 0, and it is your loudest finding. It stays
  in the report: deleting the row is how a codebase with no evals gets a clean
  audit.
- **`n/a` plus a trigger** — the architecture has no such seam yet, so there is
  nothing to score. The trigger is the whole point: the first fan-out, the first
  uploaded document, the second tenant. `n/a` with no trigger decays into "we
  forgot" inside two quarters.
- **`not yet probed`** — you ran out of budget, and the summary says so. This one
  is for a seam you never located; a seam you *did* locate and could not measure
  keeps its symbol here and scores `—`, which [`TIME-BOXED-RUN.md`](TIME-BOXED-RUN.md) works through.

A blank cell is a seam you forgot.

→ `OUTPUT-SHAPES.md` — the nine-row table, the ten coverage lines, one finding
block and a worked ranking, all filled in against an invented codebase. Open it
before writing the first row; it is the shape of everything this audit produces.

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

→ [`SCORING.md`](SCORING.md) — why nothing strong lifts a row, across jobs and
inside one; the two measurements (add a call site the way a newcomer would; delete
the control and run the suite) that decide 1 vs 2 and 2 vs 3; and the probes that
change the system, including the one a branch cannot undo. Open it before writing
the first score.

### Step 3 — Answer the ten coverage items

Output is ten lines, one per item, each carrying four fields: the item, the seam
holding its control, that seam's score, and the verdict — **covered**,
**uncovered**, **n/a** plus the trigger that ends it, or **unmeasured** where a
time-boxed run left that seam with no score. An item with a blank verdict is not
answered, and a verdict with no score behind it is an opinion — `unmeasured` is
the single exception, and it is honest exactly because it says so in the score
field instead of putting a number there. Where an item has a second
seam, name it on the same line. Four fields and four verdict tokens are easy to
improvise differently every run, so don't: `OUTPUT-SHAPES.md` carries all ten
worked.

These ten lines are the second block of the committed file, between the table and
the plan, and they are written last — once every seam carries a score, or once
the run has stopped probing. The map reads scores, so a line written ahead of its
seam is a guess; `unmeasured` is what you write instead of guessing, and never
what you write for a seam you simply did not think about.

→ `OWASP-COVERAGE.md` — the ten items of the OWASP Top 10 for Agentic
Applications 2026 mapped to seams, the rule that turns a score into one of the
four verdicts, the second seam several items also lose ground at, and the
triggers for the three that do not apply to a single-agent application.

### Step 4 — Blast radius

Severity labels do not rank. Two axes do — **reach** (can something an attacker
controls get to this seam) and **reversibility of the incident the gap permits** —
with silent-before-loud as the tie-break → [`RANKING-AND-PLAN.md`](RANKING-AND-PLAN.md).

### Step 5 — The plan

Every finding names its seam, its score and target, the previous audit's score,
its probe output, its rank inputs, the smallest change sized in files, and the test
that proves it closed. **Cap the plan at ten findings** → the fields, the two
entries that exist for a fallen seam and a two-seam defect, and the unscored-seam
case are in [`RANKING-AND-PLAN.md`](RANKING-AND-PLAN.md).

### Step 6 — Commit the three blocks

Table, coverage lines, capped plan, into one file in the repository, on the
commit you audited. An audit that ends in a chat window cannot be diffed, and the
demotion from 2 to 1 is only ever visible as a diff. [`ARTEFACT.md`](ARTEFACT.md) says
where it lives and what re-runs it.

## Report first, fix second

Land the whole report before changing a line. An audit that stops to fix its
second finding never reaches its ninth seam — and the ninth is exactly where
absence hides, because it is the seam nobody built and therefore nobody
remembers.

## When you have two hours, not two days

A partial audit is a deliverable. Step 1 runs in full; Step 2's budget buys four
seams and six probes first; a probe you did not run writes 0 or `—`, never a rung
between; and all three blocks are still committed →
[`TIME-BOXED-RUN.md`](TIME-BOXED-RUN.md).

## The artefact, and when you run it again

One committed file at `docs/agentic-audit.md` — or a one-line pointer there naming
the real path — holding the stamped table, the ten coverage lines and the capped
plan, re-run on a trigger rather than on memory →
[`ARTEFACT.md`](ARTEFACT.md): the stamp, the pointer rule and the triggers.

## Done when

- every seam reads as a `file:symbol` with a score of 0–3, or `absent` with 0, or
  `n/a` plus its trigger, or `not yet probed` — and, on a time-boxed run only, a
  `file:symbol` scored `—` because the seam was located and never measured; nine
  rows, no blank cell;
- every score is the **lowest scored probe** at that seam, and the evidence names
  which probe set it — on a time-boxed run, the lowest *observed* probe, which
  stands as a score only at 0;
- every 2 was earned by adding a call site the naive way and watching the control
  cover it unprompted; every 3 by deleting the control and watching a named test
  go red — at **evals**, by making the gate non-blocking and watching the merge be
  refused;
- every finding quotes the probe output it came from, not a judgement about the
  code;
- every finding names a change sized in files touched, the test that closes it,
  and this seam's score in the previous audit or `first run`;
- all ten coverage items carry a seam, that seam's score and a verdict — and on a
  short run, `unmeasured` wherever that seam carries no score, never a blank line
  and never a guessed one;
- the table head carries this run's date, branch and commit and the previous
  run's, and all three blocks — table, coverage lines, capped plan — are
  **committed** at `docs/agentic-audit.md`, or at a path a one-line stub committed
  there points to.

A seam at 3 is proved by a test, not by production behaviour. Whether the control
is tuned *right* — a threshold, a budget, a refusal rate — is the evals seam's
question, answered with a dataset rather than with this audit.

Any threshold you did not measure on this codebase is illustrative, and the
report says so. A measured-looking number nobody measured is the one thing that
gets an otherwise correct plan thrown out.
