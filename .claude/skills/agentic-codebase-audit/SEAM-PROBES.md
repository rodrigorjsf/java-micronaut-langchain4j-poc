# Seam probes

One block per seam, in the order of the table in `SKILL.md`. Every probe is
written so that a small, empty or successful answer is the finding. **Record the
actual output beside each one — a probe with no recorded output was not run.**

Probes 1.3, 4.1 and 7.3 deliberately break the configuration to watch what
happens, and 6.1 writes to the real store. Run them on a scratch branch, against
a scratch conversation, and revert.

Numbers marked *illustrative* are there to make a count meaningful, not to be
quoted. Measure yours.

## 1. Model access

1. **Count the construction sites** of the provider client or chat-model object.
   More than one, or any inside a request-scoped path, is the finding. Each
   request then pays a fresh TLS handshake — illustratively 150–250 ms bolted
   onto a first-token latency already near a second — and each instance carries
   its own retry state, so a configured "3 retries" becomes 3 per concurrent
   request with nothing coordinating them against a provider that is already
   rate-limiting you. *Fix: one instance built at startup, injected.*
2. **Count literal occurrences of the model identifier string.** More than one
   and model choice is not a deployment decision: swapping models is a code
   change and a release. *Fix: address models by role and resolve the identifier
   in one place — the service-composition skill owns the shape.*
3. **Boot with an identifier that does not exist.** Starting is the finding: the
   failure has moved to the first user request after the deploy. *Fix: one
   resolve-and-fail call at boot.*
4. **Find the request timeout and the connection cap, and write both numbers
   down.** Absent in code means the SDK default, and across current SDKs those
   run from ten minutes to none — look yours up rather than assuming it is sane.
   Then multiply by your concurrency: that product is how many threads one hung
   upstream can hold. With no timeout, load-shedding never fires, because nothing
   ever fails.

## 2. Prompt assembly

Doctrine belongs to the service-composition skill. These four establish facts.

1. **Count the assembly points.** Two places that build standing instructions
   means two prompts in production, and one of them is reviewed by nobody.
2. **Trace the provenance of each block** of the standing prompt: a literal in
   the source, a configuration value, a database row, a document someone can
   edit, a template rendering a user-supplied field. Anything an input can reach
   is an **ASI01 Agent Goal Hijack** finding *at this seam*, not at guardrails:
   text that arrives as instructions was never on a path a guardrail watches.
3. **Find the first volatile byte.** Serialize the exact prompt prefix on two
   consecutive turns and diff them. A cached prefix ends at the first differing
   byte, so a timestamp, a rendered locale or a turn counter makes everything
   *after* it uncached on every request — and the higher it sits, the more you
   lose. Record the byte offset. *Fix: move volatile content below the stable
   block, or out of the prompt.*
4. **Ask what it takes to change the standing prompt** — a deploy, or an edit to
   a row. If it is a row, ask what reviews the edit and what would notice it.

## 3. Tool boundary

Doctrine belongs to the tool-boundary skill. These are inventory queries.

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
   also **ASI05 Unexpected Code Execution**.)
4. **Read the framework's *default* tool-error handler**, not yours, and record
   verbatim what the model would see. Assume nothing here.
5. **Trigger the largest response each tool can produce and record the bytes.**
   Compare against the per-source budget; having no budget is itself the finding.
   Illustrative bands for the report: over ~10 KB is a plan entry, over ~100 KB
   is the top of this section.
6. **Table every tool against what it may reach** — network egress, the
   credential it runs under, whether it can write. No scope column anywhere is
   itself the finding (**ASI03 Identity & Privilege Abuse**).
7. **Count tools with no test at all.** A tool is a public API with a stochastic
   caller.

## 4. Discovery — tools and skills

Routing quality — which tool the model picks — belongs to the tool-and-skill
sweep. Disclosure economics belong to the tool-disclosure skill. Five facts here.

1. **Point a catalogue entry at a name that does not exist, or duplicate a name,
   and boot.** A successful boot is the finding: the catalogue is one typo from a
   capability that silently is not there, and the symptom arrives weeks later as
   the agent answering from memory instead of calling the tool — which reads as a
   bad model, not a bad load. *Fix: one startup pass resolving every reference,
   refusing to start on a miss, with the offending name in the message.*
2. **Serialize the schemas your framework actually sends and count the tokens.**
   Write your estimate down *before* you count; the gap is the finding, because
   that block is paid on every turn of every conversation. This probe establishes
   the standing cost only.
3. **Count the entries, then ask what adds the next one.** A literal list in a
   constructor means entry 40 arrives by editing that constructor, and nobody
   re-runs probe 4.2 on the way past.
4. **Ask where entries come from.** Anything resolved from outside the build is
   **ASI04 Agentic Supply Chain Vulnerabilities** and needs pinning.
5. If the visible tool set is **reconstructed each turn from the conversation**,
   run probe 6.1 and confirm the activation marker survives your real store. This
   defect spans two seams and is invisible in either one alone.

## 5. Guardrails

A **census**, not a quality judgement — quality belongs to the injection-layers
skill.

Enumerate every path by which text the user did not type reaches (a) the next
prompt, (b) the user, (c) a tool argument. The usual roster: tool results,
retrieved chunks, conversation history loaded from a store, a classifier's
structured fields, another agent's output, uploaded documents, content generated
by an upstream API. One column per row: **is anything checked here, and on which
side?**

Two finding shapes, both invisible file-by-file because every file that has a
check has a correct one:

- every check on the inbound user message and none on any other row — and those
  other rows are exactly the ones an attacker can write to without ever holding
  an account (**ASI01 Agent Goal Hijack**);
- a check that runs and whose verdict nothing acts on.

Then the **outbound half**, which almost every audit skips because the inbound
side is the one everybody remembers to build. Run three turns and read what the
user actually receives, not what the code intends:

1. a tool result large enough to be cut — is the cut announced, or is a fragment
   handed over as though it were the whole list?
2. a refused request — does the reply read as a refusal, or as an answer?
3. an action with a real-world consequence — did anything ask first, and did it
   show *what will happen* rather than the model's account of why it should?

Any "no" is **ASI09 Human-Agent Trust Exploitation**: the user is being invited
to trust output that has not earned it.

*Fix, in order: the single outbound path that reaches the user, then the inbound
path carrying the most untrusted bytes.*

## 6. Memory

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
4. **Ask for the invariant list** — the state that may never be summarised or
   evicted, because losing it silently changes behaviour rather than raising. No
   list is the finding. Write one during the audit: it takes ten minutes and it
   is the input to every future compaction change.
5. **Find the bound** — message count, token budget, or nothing. Nothing means
   the bound is the provider's context limit, and the failure lands first on your
   longest-running conversation, which belongs to your most engaged user.
6. **Ask what a failed cache write in front of the store does.** Continuing
   silently loses a turn with no error anywhere.

## 7. Evals

Three answers, in order. A "no" stops the seam at 0.

1. Is there a committed dataset of cases with expected outcomes?
2. Does CI run it?
3. **Would a deliberate one-word change to the standing prompt fail something?**
   Make the change on a branch and run it.

The third is the probe; the first two are paperwork. Prompts ship with the unit
tests green, because unit tests do not read prompts — and a suite that a prompt
change cannot fail is worse than no suite, because it gets cited in review as
evidence.

*Fix: three cases in CI that a prompt change breaks. Three is not a good suite —
it is the difference between zero and non-zero.*

## 8. Observability

1. **Take one real request id from yesterday and reconstruct the whole turn from
   telemetry alone**: every model call, every tool call and its outcome, every
   routing decision, every refusal. Whatever you cannot reconstruct is the
   finding — it is the part you will be missing during the incident, and it is
   why every other finding in this audit is unmeasurable until it is fixed. You
   have no way to watch a control work, or stop working.
2. **Ask someone to decompose last month's model bill** by call site, model and
   feature. If nobody can, the seam is absent here. How to build it belongs to
   the cost-observability skill.
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

## 9. Sub-agents

1. **Count the hops one request can take, and multiply.** Depth 3 with fan-out 4
   is 4³ = 64 leaf model calls for one user turn. A child that can invoke its own
   child has no ceiling at all, and the incident presents as a runaway bill
   rather than as an error (**ASI08 Cascading Failures**).
2. **Ask whose credentials a sub-agent runs under and which tools it inherits.**
   Inheritance by default hands a summarising sub-agent the write tools of its
   parent (**ASI03**).
3. **Ask where each sub-agent's raw output goes.** Re-entering the parent
   conversation verbatim means the sub-agent is spending context rather than
   saving it — usually the only reason it was built.
4. **Ask whether the child's output is treated as data or as instructions**, and
   whether the channel between agents is authenticated or merely internal
   (**ASI07 Insecure Inter-Agent Communication**).
5. **If the codebase has none**, record `absent` with its trigger: the first
   fan-out, or the first agent addressed over a wire.
