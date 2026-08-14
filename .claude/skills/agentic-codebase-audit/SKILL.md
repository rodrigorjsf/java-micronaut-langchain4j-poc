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
point at.

Four absences, none of which looks like a bug in any single file:

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

The GOOD form produces evidence you can paste. The BAD form produces an opinion,
and an opinion loses the argument with whoever wrote the code.

This audit names **checks, not rules**. Where your catalogue already has a skill
owning a seam's doctrine — the tool boundary, tool disclosure, injection layers,
retrieval, cost accounting, service composition, the tool-and-skill sweep — the
probe establishes the fact and points there for the fix.

## The nine seams

Frameworks name everything differently, so locate by **shape**, never by keyword.

| Seam | Find it by shape | The absence that hides there |
|---|---|---|
| **Model access** | follow one inbound request to the first call on the provider SDK's client type, whatever it is called here; note where that client is *constructed*, not only called | built per request; no bound on the call |
| **Prompt assembly** | whatever composes the standing instructions before that call; finding two such places is already the finding | configuration or user data reaching the standing instructions |
| **Tool boundary** | the widest outbound fan-out in the repository: every function annotated, registered or otherwise published to a model | no record of what each tool may reach |
| **Discovery** | whatever assembles the tool and skill list handed to the model each turn; if nothing assembles it, the list is a constant somewhere — find it | a load failure that is not a boot failure |
| **Guardrails** | anything that reads text and can refuse; note which side | every one of them on the inbound side |
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

**Step 1 — Locate.** Work the shape column, seam by seam. Output is nine rows,
each naming a `file:symbol` or the word `absent`.

A blank cell is a seam you forgot. `absent` is your loudest finding and it stays
in the report — deleting the row is how a codebase with no evals and no hop
budget gets a clean audit. **Every `absent` carries the trigger** that will make
it applicable: the first fan-out, the first uploaded document, the second tenant.
An absence without a trigger decays into "we forgot" inside two quarters.

**Step 2 — Probe, and score what you find.**

| Score | State | What you may claim |
|---|---|---|
| 0 | absent | nothing does this job — the evidence is the empty query |
| 1 | local | one call site does it; the next one added will not |
| 2 | structural | one owner, type or registration point makes it unbypassable |
| 3 | proved | a named test goes red when the control is removed |

The gap between 2 and 3 is where an audit gets lied to, so measure it instead of
reading for it: **delete the control locally and run the suite.** If nothing goes
red the score is 2, whatever the code looks like. Restore it, and record which
test you expected to fail.

Which is also why 2 is not the finish line. A control at 2 decays to 1 quietly:
nobody deletes it — someone adds the twelfth tool that does not route through the
registration point the other eleven share, and the layer now has eleven enforced
paths and one that is not.

Four probes and the score measurement deliberately break the system to watch what
it does. Run them on a scratch branch and revert. "Report first, fix second"
governs findings, not probes — a probe that mutates and reverts is measurement.

The nine rows are the artefact. Three of them, filled:

```
Seam            Where                          Score  Evidence
model access    agent/ClientFactory:41         1      one construction site, inside the request
                                                      handler; 0 hits for a timeout setting
guardrails      absent on the outbound side    0      12 inbound checks; 0 on any path back to
                                                      the user. Trigger: already applicable
sub-agents      not yet probed                 —      two-hour run; step 1 found no fan-out
```

`not yet probed` and `absent` are opposite claims. They never share a cell.

**Step 3 — Apply the coverage lens.**

→ `OWASP-COVERAGE.md` — the ten items of the OWASP Top 10 for Agentic
Applications 2026, each mapped to the seam that would hold its control. Open it
once every seam carries a score: the map reads scores, so running it earlier
produces guesses.

**Step 4 — Rank by what each finding costs if it is left.** Below.

**Step 5 — Write the plan.** Below.

## Blast radius

Severity labels do not rank. Two axes do, both answerable from probe output.

**Reach** — can something an attacker controls get to this seam? Their text, or
an identifier they supply. Uploaded documents, retrieved pages, tool responses
and sub-agent replies are attacker-writable in some deployment; name which.

**Reversibility** — of the **incident the gap permits**, not of the gap itself.
A missing error taxonomy is not "irreversible"; the wasted retries it causes are
reversible, and the cross-tenant read a missing partition key permits is not.
Classify the incident, or two auditors score the same finding differently.

| | Irreversible incident | Reversible incident |
|---|---|---|
| **Attacker-reachable** | rank 1 | rank 2 |
| **Internal only** | rank 3 | rank 4 |

Inside a rank, in this order: **silent before loud**, then every-request before
rare-path, then cheapest fix first. Silence is the tie-break that matters — a
seam that fails loudly gets fixed by whoever is on call; a seam that fails
silently is still failing a year later and the first person to notice is a
customer.

## The plan

One block per finding, ranked. A block missing a field is not ready to hand to
anyone:

```
FINDING 1 — Conversation memory has no tenant partition
Seam             memory
Score            0 → target 3
Evidence         the key is "chat:" + conversationId; 0 hits for a caller or
                 tenant id anywhere in the store package
Reach            attacker-reachable — the conversation id arrives in the request
Reversibility    irreversible — a conversation read by the wrong person cannot
                 be unread
Silence          silent — a cross-tenant read returns a valid conversation and
                 raises nothing
Cost if left     one identifier bug, or one enumeration, hands a customer another
                 customer's conversation, and you hear about it from them
Smallest change  put the caller id in the key, and refuse a read whose key does
                 not match the caller — 1 file
Proved by        a test that writes as caller A, reads as caller B, and asserts
                 the read fails
Coverage         ASI06, with ASI03 at the second seam
```

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

A partial audit is a deliverable. An all-or-nothing procedure on a large
codebase produces nothing at all.

Probe these four first. They need no running system, and they are where absence
is both most common and most expensive:

1. **Guardrails**, the census half — every path carrying text the user did not
   type, and which of them has a check.
2. **Memory**, the tenancy probe — read the key builder.
3. **Tool boundary**, the destination-parameter list and the framework's default
   error handler.
4. **Model access**, the construction sites and the timeout.

Mark the other five `not yet probed`, name them in the summary, and stop.

## The artefact, and when you run it again

The deliverable is a committed file: the nine-row scored table at its head, the
capped plan under it. Committed, because an audit compounds only if the next one
can `diff` against the last — a seam that went from 2 to 1 is the cheapest
finding you will ever get, and it is invisible without the previous table.

Re-run on a trigger, not when someone remembers:

- a tool is added, or an existing tool's authority widens;
- a framework or provider SDK major version;
- a new model or a new provider;
- the first agent-to-agent hop, or the first unattended run.

## Done when

- all nine seams carry a score of 0–3 with an entry point, or `absent` plus its
  trigger, or `not yet probed`;
- every 3 was earned by deleting the control and watching a named test go red;
- every finding quotes the probe output it came from, not a judgement about the
  code;
- every finding names a change sized in files touched and the test that closes
  it;
- all ten coverage items are answered against a scored seam.

A seam at 3 is proved by a test, not by production behaviour. Whether the control
is tuned *right* — a threshold, a budget, a refusal rate — is the evals seam's
question, answered with a dataset rather than with this audit.

Any threshold you did not measure on this codebase is illustrative, and the
report says so. A measured-looking number nobody measured is the one thing that
gets an otherwise correct plan thrown out.
