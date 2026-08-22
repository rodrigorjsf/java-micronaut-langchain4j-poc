# Seam probes

One block per seam, in the order of the table in `SKILL.md`. Every probe is
written so that a small, empty or successful answer is the finding. **Record the
actual output beside each one — a probe with no recorded output was not run.**

Probes 1.3, 4.1, 5.3, 6.6 and 7.3 deliberately break something to watch what
happens, 6.1 writes to the real store, and the gate flip that earns evals a 3
turns a required check off. Run them on a scratch branch, against a scratch
conversation, and revert.

**3.8 is the one a revert does not cover.** Its cross-caller variant makes a live
tool read data belonging to somebody else, and that read has happened before you
switch the branch back. Point it at a scratch tenant whose data is yours, or at a
stubbed downstream — never a real caller's id. Its other two arguments, a negative
count and an absurd date range, are harmless: the hazard is that one value, not
the probe. Skip 3.8 and ASI02 loses the clause `OWASP-COVERAGE.md` keys on it.

**Each numbered probe is one job, and the seam's score is the lowest of them**
(`SKILL.md`, Step 2). A probe marked **fact-only** establishes a count, a cost or
an offset rather than a control, so it carries no score and cannot drag a seam
down — it feeds the plan instead. Evals is the one seam scored as a single job,
and it says so.

Numbers marked *illustrative* are there to make a count meaningful, not to be
quoted. Measure yours.

## 1. Model access

Doctrine belongs to `agentic-service-composition`. These four establish facts.

1. **Count the construction sites** of the provider client or chat-model object.
   More than one, or any inside a request-scoped path, is the finding. Each
   request then pays a fresh TLS handshake — illustratively 150–250 ms bolted
   onto a first-token latency already near a second — and each instance carries
   its own retry state, so a configured "3 retries" becomes 3 per concurrent
   request with nothing coordinating them against a provider that is already
   rate-limiting you. *Fix: one instance built at startup, injected.*
2. **Count literal occurrences of the model identifier string.** More than one
   and model choice is not a deployment decision: swapping models is a code
   change and a release. *Fix: run `agentic-service-composition` — addressing a
   model by role, and resolving the identifier in one place, is its shape.*
3. **Boot with an identifier that does not exist.** Starting is the finding: the
   failure has moved to the first user request after the deploy. *Fix: one
   resolve-and-fail call at boot.*
4. **Find the four bounds *this codebase chose* on a model call, and write each
   number down** — request timeout, connection cap, retry budget, and what opens
   the circuit instead of retrying into a dependency that is already failing. The
   job is the choosing: nothing set anywhere scores 0 however sane the SDK's own
   number turns out to be, because whatever the SDK defaults to is now your bound
   and nobody read it. Across current SDKs those defaults run from ten minutes to
   none. Then multiply the timeout by your concurrency: that product is how many
   threads one hung upstream can hold. With no timeout at all, load-shedding never
   fires, because nothing ever fails. The last two are what **ASI08 Cascading
   Failures** rests on at this seam: retries with nothing to stop them turn one
   slow provider into a self-inflicted load test, and each retry is billed. *Fix:
   choose all four at the one construction site; `agentic-tool-boundary` owns what
   a bounded retry looks like.*

## 2. Prompt assembly

Doctrine belongs to `agentic-service-composition`. Whether the content in the
prompt *belongs* there rather than in a corpus is `prompt-to-corpus-migration`'s
question — a model cannot load it: ask the user to run
prompt-to-corpus-migration, which they invoke by name. These four establish
facts.

1. **Count the assembly points.** Two places that build standing instructions
   means two prompts in production, and one of them is reviewed by nobody.
2. **Trace the provenance of each block** of the standing prompt: a literal in
   the source, a configuration value, a database row, a document someone can
   edit, a template rendering a user-supplied field. Anything an input can reach
   is an **ASI01 Agent Goal Hijack** finding *at this seam*, not at guardrails:
   text that arrives as instructions was never on a path a guardrail watches.
3. **Find the first volatile byte** *(fact-only)*. Serialize the exact prompt prefix on two
   consecutive turns and diff them. A cached prefix ends at the first differing
   byte, so a timestamp, a rendered locale or a turn counter makes everything
   *after* it uncached on every request — and the higher it sits, the more you
   lose. Record the byte offset. *Fix: move volatile content below the stable
   block, or out of the prompt.*
4. **Ask what it takes to change the standing prompt** — a deploy, or an edit to
   a row. If it is a row, ask what reviews the edit and what would notice it.

## 3. Tool boundary

Doctrine belongs to `agentic-tool-boundary`. These are inventory queries.

1. **Count the distinct outcome shapes** the layer can return. One or two means
   there is no error taxonomy: every failure looks identical to the model, so it
   retries the source that is down and gives up on the one that only needed a
   corrected argument — and your dashboards have one counter, with no way to see
   which source fails or how often.
2. **Count the distinct code paths that turn a failure into text the model
   reads.** *N* tools with *N* paths means no boundary exists: each tool
   improvises, and every one of them looks fine alone.
3. **List every parameter whose value could name a destination** — URL, host,
   file path, connection string, query text, shell command. The list should be
   empty; whatever is on it is the first thing in the plan. (**ASI02 Tool Misuse
   & Exploitation**; anything that evaluates, templates, renders or shells out is
   also **ASI05 Unexpected Code Execution**.) For each survivor, record
   **where it executes and what ends its blast radius** — the process the request
   is served from, the identity it holds, what it can reach on the network. "In
   the application process, as the application user" is the finding, and it is the
   half of ASI05 that an allowlist of parameters does not answer.
4. **Read the framework's *default* tool-error handler**, not yours, and record
   verbatim what the model would see. Assume nothing here.
5. **Trigger the largest response each tool can produce and record the bytes.**
   Compare against the per-source budget; having no budget is itself the finding.
   Illustrative bands for the report: over ~10 KB is a plan entry, over ~100 KB
   is the top of this section.
6. **Table every tool against what it may reach** — network egress, the
   credential it runs under, whether it can write. Ask of the credential column
   whether it is the **caller's** authority or **one shared service identity**:
   the second means a tool can read what the person who asked for it could not,
   and the audit trail names the service (**ASI03 Identity & Privilege Abuse**).
   No scope column anywhere is itself the finding.
7. **Count tools with no test at all.** A tool is a public API with a stochastic
   caller.
8. **Send one tool an argument its signature accepts and its contract forbids** —
   a negative count, an id belonging to another caller (a scratch tenant of your
   own, or a stubbed downstream — see the note at the top of this file), a date
   range spanning ten years — and record **what objected, and where**. Rejected at the boundary is
   the answer you want. Rejected inside the tool body is one tool's diligence, not
   a boundary, and scores as the weakest path across the set. Nothing objecting
   until the downstream system does means the model can drive that system directly
   through the tool, one plausible-looking argument at a time (**ASI02**). What a
   validated argument looks like belongs to `agentic-tool-boundary`; this probe
   only records where the check is, and how many tools have none.

## 4. Discovery — tools and skills

Routing quality — which tool the model picks — belongs to
`reviewing-agent-tools-and-skills`. Disclosure economics belong to
`progressive-tool-disclosure`. Five facts here.

1. **Point a catalogue entry at a name that does not exist, or duplicate a name,
   and boot.** A successful boot is the finding: the catalogue is one typo from a
   capability that silently is not there, and the symptom arrives weeks later as
   the agent answering from memory instead of calling the tool — which reads as a
   bad model, not a bad load. *Fix: one startup pass resolving every reference,
   refusing to start on a miss, with the offending name in the message.*
2. **Serialize the schemas your framework actually sends and count the tokens**
   *(fact-only)*. Write your estimate down *before* you count; the gap is the
   finding, because that block is paid on every turn of every conversation. It
   establishes the standing cost and scores nothing.
3. **Count the entries, then ask what adds the next one.** A literal list in a
   constructor means entry 40 arrives by editing that constructor, and nobody
   re-counts 4.2 on the way past — fact-only scores nothing and is still the
   block every turn of every conversation pays for.
4. **Ask where entries come from, and for anything resolved from outside the
   build, write down what pins it** — a version, a digest, a lockfile line, a
   signature. "The latest one on that endpoint" is the finding: the definition the
   model was given last week is not the one it will be given tonight, nothing in
   your repository changed, and no review saw the difference (**ASI04 Agentic
   Supply Chain Vulnerabilities**).
5. If the visible tool set is **reconstructed each turn from the conversation**,
   run probe 6.1 and confirm the activation marker survives your real store. This
   defect spans two seams and is invisible in either one alone.

## 5. Guardrails

A **census**, not a quality judgement. Detector quality belongs to
`prompt-injection-layers`; whether this codebase should retrieve at all belongs
to `retrieval-that-earns-its-place`; what a classifier does when it cannot answer
in time belongs to `llm-triage-gate`. Three probes, and the seam takes the
lowest.

**5.1 — The inbound census.**
Enumerate every path by which text the user did not type reaches (a) the next
prompt, (b) the user, (c) a tool argument. The usual roster: tool results,
retrieved chunks, conversation history loaded from a store, a classifier's
structured fields, another agent's output, uploaded documents, content generated
by an upstream API. One column per row: **is anything checked here, and on which
side?**

If retrieval feeds any of those rows, that row carries two extra facts. **Who can
write to the corpus** — an ingestion path fed by user uploads or crawled pages is
an inbound attack surface that needs no account, and its text lands in the prompt
having passed no check on this list. **Does the similarity threshold ever
refuse** — count the queries over one real day that returned nothing. A count of
0 means there is no threshold, only a ranking: every question retrieves
something, and the model answers from the three least-irrelevant chunks in the
index with no signal that it is doing so.

Two finding shapes, both invisible file-by-file because every file that has a
check has a correct one:

- every check on the inbound user message and none on any other row — and those
  other rows are exactly the ones an attacker can write to without ever holding
  an account (**ASI01 Agent Goal Hijack**);
- a check that runs and whose verdict nothing acts on.

**5.2 — The outbound half**, which almost every audit skips because the inbound
side is the one everybody remembers to build. Run three turns and read what the
user actually receives, not what the code intends:

a. a tool result large enough to be cut — is the cut announced, or is a fragment
   handed over as though it were the whole list?
b. a refused request — does the reply read as a refusal, or as an answer?
c. an action with a real-world consequence — did anything ask first, and did it
   show *what will happen* rather than the model's account of why it should?

Any "no" is **ASI09 Human-Agent Trust Exploitation**: the user is being invited
to trust output that has not earned it.

**5.3 — What the check does when it cannot answer.** Every check on 5.1's census
is code that can throw, and most are a network call to a model that can be slow
or rate-limited. Break each one in turn — make it raise, then make it exceed its
timeout — and send a request it would have refused. Three outcomes, and you have
to run it to know which one you have:

- the request was served, and nothing recorded that the check did not run —
  **fail-open and silent**, the traffic is unprotected and the graph still reads
  green;
- the request was served, and something recorded it — fail-open, deliberate or
  not, and at least countable;
- the request was refused — fail-closed, and now measure how often that happens,
  because a detector that fails 1% of the time is a 1% outage.

Nothing in any file is wrong in the first case, which is why this is the absence
that survives code review: the handler that swallows the exception is the same
handler a careful engineer wrote for every other dependency. Record the outcome
and the recovery path; *whether* it should fail open is `llm-triage-gate`'s
trade-off, and it is a different conversation from "nobody chose".

*Fix, in order: the single outbound path that reaches the user, then the check
that fails open silently, then the inbound path carrying the most untrusted
bytes.*

## 6. Memory

Doctrine belongs to `conversation-memory-and-compaction`. These six establish
facts.

1. **Round-trip a realistic conversation through the real store** — write, read,
   diff. Anything dropped is the finding. A serializer that keeps only message
   text drops attributes, tool-call records and disclosure state; it compiles, it
   passes every test that uses an in-memory store, and production forgets. Use a
   conversation long enough to be real — illustratively 30 to 40 turns — not the
   two-turn fixture the unit tests use.
2. **Read the key builder and find what partitions one caller's conversation from
   another's.** A key with no tenant or session component is one identifier bug
   away from cross-user bleed, and nothing in the type system objects. Then the
   second question: what happens on a read whose key does not belong to the
   caller — is it checked, or is holding the key the whole authorisation?
   (**ASI06 Memory & Context Poisoning**, and **ASI03** at the second seam.)
3. **Enumerate every writer to the durable store.** Tool results or retrieved
   chunks on that list means attacker-controlled text replays into every future
   turn of that conversation, long after the source that carried it is gone.
4. **Ask for the invariant list**, and record who produced it and from where. No
   list, or a list assembled on the spot to answer you, is the finding: the
   compaction test has nothing to assert, so every compaction change ships
   unproven. What belongs on one is `conversation-memory-and-compaction`'s
   question, not this audit's.
5. **Find the bound and write down which kind it is** — message count, token
   budget, or nothing. Nothing means the bound is the provider's context limit,
   and the failure lands first on your longest-running conversation, which
   belongs to your most engaged user. Which kind it *should* be is
   `conversation-memory-and-compaction`'s trade-off; the probe records what is
   there.
6. **Fail a cache write in front of the store, then take the next turn** and read
   what the model saw. A turn built from a history the user never had is the
   finding, and it raised nothing on the way.

## 7. Evals

Doctrine belongs to `agentic-evals`. This seam is **one job**, not a list of
them; the three answers below feed it, and the ladder at the end of the section
turns them into the score.

1. Is there a committed dataset of cases with expected outcomes?
2. Does CI run it?
3. **Would a deliberate one-word change to the standing prompt fail something?**
   Make the change on a branch and run it.

The third is the probe; the first two are paperwork. Prompts ship with the unit
tests green, because unit tests do not read prompts — and a suite that a prompt
change cannot fail is worse than no suite, because it gets cited in review as
evidence.

**The rungs here are measured against the gate, not against a test.** Everywhere
else a 3 is earned by deleting the control and watching a test go red; at this
seam the control *is* the test suite, so that measurement eats itself. Substitute
the gate:

| Score | State at this seam | How you measured it |
|---|---|---|
| 0 | missing | any of the three answers above is "no" |
| 1 | local | all three yes, and cases exist for the prompts somebody remembered — add a second prompt or a new tool description and it ships with no case |
| 2 | structural | that new prompt or tool description **cannot** reach production uncased: a build check ties each model-facing surface to at least one case, and you watched it refuse the uncased one |
| 3 | proved | the gate is itself protected — make the eval job non-blocking on a branch, open the change, and the merge is refused |

The 2→3 step is the one nobody has. A green suite that any hurried change can
mark `continue-on-error` is a control with an off switch and no alarm on it.

*Fix, at 0: three cases in CI that a prompt change breaks. Three is not a good
suite — it is the difference between zero and non-zero, and
`agentic-evals` owns what a good one looks like.*

## 8. Observability

Doctrine for the cost half belongs to `llm-cost-observability`; the rest of this
seam owns no doctrine anywhere, it is plumbing. These six establish facts.

1. **Take one real request id from yesterday and reconstruct the whole turn from
   telemetry alone**: every model call, every tool call and its outcome, every
   routing decision, every refusal. Whatever you cannot reconstruct is the
   finding — it is the part you will be missing during the incident, and it is
   why every other finding in this audit is unmeasurable until it is fixed. You
   have no way to watch a control work, or stop working.
2. **Ask someone to decompose last month's model bill** by call site, model and
   feature. If nobody can, nothing at this seam accounts for cost — score it on
   that path, not on the one that works. How to build it belongs to
   `llm-cost-observability`.
3. **Check that a refusal emits something.** Blocks that log nothing are
   invisible until a user complains, and the behavioural baseline you would alert
   against does not exist (**ASI10 Rogue Agents**).
4. **Check that accounting code cannot throw into the request path, and that it
   says so when it fails.** A listener that swallows its own exception is a silent
   hole in the numbers rather than an error — and those numbers are what you will
   use to justify the next change.
5. **Count the distinct values each metric label fed by model output can take.**
   "Whatever the model wrote" is unbounded: first a metrics bill, then dropped
   series, then the dashboard you were going to use during the incident.
6. **List every way a turn can start.** An inbound request with a person waiting
   on it is one. A schedule, a webhook, a queue message, a retry of a failed job,
   another agent, or anything holding a standing credential is another — and each
   one is a run that can go wrong with nobody watching. Two things come out of
   this list. It **decides whether ASI10 Rogue Agents applies at all**: an
   application whose every turn has a person waiting reports `n/a` with this probe
   as the evidence, and one nightly job ends that. And for each non-human trigger,
   record whether telemetry names **which trigger fired and on whose behalf** —
   an unattended run you cannot attribute is one you cannot alert on, and it is
   the run most likely to be somebody else's.

## 9. Sub-agents

Doctrine belongs to `subagent-context-isolation`. These five establish facts.

1. **Count the hops one request can take, and multiply.** Depth 3 with fan-out 4
   is 4³ = 64 leaf model calls for one user turn. Then write down the three
   ceilings and where each lives: **hops**, **steps per run**, **spend per run**.
   A ceiling each new child receives a fresh copy of is not one — it has to be
   decremented somewhere the whole run shares. Missing entirely, the incident
   presents as a runaway bill rather than as an error (**ASI08 Cascading
   Failures**); the fourth bound, the retry that re-enters a model call, is probe
   1.4 at model access.
2. **Ask whose credentials a sub-agent runs under, then diff its tool set against
   its parent's** and record what the child holds and never calls. Inheritance by
   default hands a summarising sub-agent the write tools of its parent
   (**ASI03**).
3. **Ask where each sub-agent's raw output goes** *(fact-only)*. Re-entering the
   parent conversation verbatim means the tokens the child was built to discard
   were paid twice — the child's own preamble, and then the output it was
   supposed to compress.
4. **Ask whether the child's output is treated as data or as instructions**, and
   whether the channel between agents is authenticated or merely internal
   (**ASI07 Insecure Inter-Agent Communication**).
5. **If the codebase has none**, this seam is `n/a`, not `absent` — there is no
   gap in architecture that has no sub-agents. Record `n/a` with the trigger that
   will make it a seam: the first fan-out, or the first agent addressed over a
   wire.
