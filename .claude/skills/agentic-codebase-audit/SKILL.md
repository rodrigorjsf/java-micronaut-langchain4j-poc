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
  keeps its symbol here and scores `—`, which the two-hour section works through.

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

**Nothing strong lifts a row**, and that rule has two halves. Auditors inflate
this rung by applying only the first, because the seam looks built.

**Across jobs:** a job nothing does anywhere is 0 even where the seam has code and
**Where** names a real `file:symbol` — model access holding one shared client, one
model identifier and a boot check still scores 0 when nothing in the codebase
*chose* a bound on the call. **Inside one job:** the score is that job's weakest
path — eleven tools whose failures are red-tested and a twelfth returning raw
exceptions is a 1, because the score answers what the job *guarantees*.

Drop the across-jobs half and the seam is scored by its healthiest job; drop the
inside-one-job half and one good call site speaks for all of them. Either way two
audits of the same codebase stop comparing. Most 0s therefore name a real symbol;
`absent` is the one that does not.

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

Several probes, and both measurements above, change the system to watch what it
does — breaking a configuration, failing a detector, deleting a control. Run them
on a scratch branch and revert; "report first, fix second" governs findings, not
probes. Skip them and you report those seams healthy, because the code looks
correct and only executing it disagrees.

**One probe a branch cannot undo.** Probe 3.8's cross-caller variant drives a live
tool with an id belonging to somebody else, and that read has already happened by
the time you switch branches back. Point it at a scratch tenant whose data is
yours, or at a stubbed downstream — never a real caller's id — the way 6.1 names
its scratch conversation. Its other two arguments are harmless: the hazard is that
one value, not the probe, and dropping 3.8 leaves ASI02 with nothing behind it.

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

Every finding names its seam, its score and target, **the score this seam carried
in the previous audit**, the probe output it came from, its two rank inputs and
whether it fails silently, what it costs if it is left, the **smallest change**
that closes it, the test that **proves** it closed, and the coverage items it
moves.

Two entries in that list exist for findings that otherwise have no shape to be
written in. **A seam that fell** — 2 last run, 1 now — is the cheapest finding in
the file, and only the previous score carries it; `first run` is the honest entry
when there is nothing to compare against. **A defect that spans two seams** — a
disclosure marker the store silently drops, probe 4.5, invisible in either seam
alone — names both in the seam field, the one that must change first in front,
because filed against a single seam it lands on a team that cannot close it.

An unscored seam still produces findings, the way a fact-only probe does: the
score field reads `— → target 2`, and the evidence carries the fact instead of a
rung. Probe 1.1 finding one construction site at startup, with nobody having
measured whether a new call site reaches it, is a real plan entry whose smallest
change is the measurement itself — one call site added the naive way, run once.
Dropping those because the seam has no number is how a short run becomes no run.

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
produces nothing at all. Probe these four first — they read out of the source, so
they need no running system, and they are where absence is both most common and
most expensive:

1. **Guardrails, probe 5.1** — the census of every path carrying text the user
   did not type, and which of them has a check.
2. **Memory, probe 6.2** — the key builder and what partitions one caller from
   another.
3. **Tool boundary, probes 3.3 and 3.4** — the destination-parameter list, and
   the framework's default error handler read verbatim.
4. **Model access, probes 1.1 and 1.4** — the construction sites, and the bound
   this codebase chose on the call.

**A pass that runs nothing writes 0 or `not yet probed`, and nothing between.** 0
survives a paper reading because an empty answer has no rung beneath it — no bound
chosen anywhere, no check on any row of the census, no tenant component in the
key. Every rung above 0 is one of Step 2's two measurements, so a job that turns
out to *exist* leaves that seam unscored: **Where** keeps the `file:symbol` you
located, **Score** reads `—`, and the evidence records the fact you established
and the rung nobody measured. Writing 1 because the code looks reasonable is
exactly the reading Step 2 says decides it wrong every time. The same rule covers
a seam whose probes you ran only some of: a 0 stands, because no unrun probe goes
lower, and anything above 0 waits.

Mark the other five seams `not yet probed`, and commit **all three blocks** — a
short run does not get to drop one. The table is still nine rows. The coverage
block is still ten lines: `uncovered` where the mapped seam scored 0, `unmeasured
— <seam> not yet probed` wherever that seam carries no score — ASI04 at
discovery, ASI07 at sub-agents and ASI10 at observability, unless Step 1
*located* that seam as `n/a`, which is a locate result and stands whatever the
budget was. The plan ranks what the four probes found, and the summary names
which seams you stopped at and which probe goes first next time.

## The artefact, and when you run it again

The deliverable is one committed file at `docs/agentic-audit.md`, three blocks in
this order: the stamped nine-row scored table, the ten coverage lines, the capped
plan. Any of the three left out of the file is a run that produced it and threw it
away, and the next audit's `diff` never notices, because the block was never part
of the file's shape.

Same path every run, and committed, because an audit compounds only if the next
one can `diff` against the last: a seam that went from 2 to 1 is the cheapest
finding you will ever get, and nothing but the previous table can show it to you.
Another path is fine where the repository already has a home for documents — but
then Step 1 finds nothing at the default, so **commit a one-line pointer at
`docs/agentic-audit.md` naming the real path**, in the same commit as the report.
The stub is the mechanism and not a courtesy: run 2 opens that path and nothing
else, so a path recorded only in a summary or a commit message is a pointer with
no reader, and the diff degrades to a fresh one-off report — the whole failure
this section exists to prevent. One line, pointing at a table; a stub pointing at
a stub is the loop you get for free if nobody says otherwise.

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
