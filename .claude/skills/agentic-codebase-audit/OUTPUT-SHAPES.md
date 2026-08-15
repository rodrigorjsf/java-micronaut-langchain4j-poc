# What the audit produces

The committed file has three blocks in this order — the scored table, the ten
coverage lines, the ranked findings — and this file works all three, plus the
ranking that orders the third. They are filled in against one invented codebase,
so a row in one block can be traced into the next. Copy the shapes, not the
contents.

## 1. The scored table

Nine rows, always. This is the head of the committed file, at the stable path the
next audit diffs against — `docs/agentic-audit.md`, or wherever a one-line stub
committed there points.

```
audit 2026-03-14 · branch main · commit 4f2a1c9
previous 2025-12-05 · commit 7e11b03

Seam              Where                         Score  Evidence
────────────────────────────────────────────────────────────────────────────────
model access      agent/ClientFactory:41        0      1.4: of the four bounds, 0 are set, so
                                                       the SDK's own apply and nobody picked
                                                       them — read from its docs: no request
                                                       timeout, no connection cap, 2 automatic
                                                       retries per call, no breaker. 1.1: the
                                                       one construction site sits inside the
                                                       request handler — a 1 alone
prompt assembly   agent/PromptBuilder:18        2      2.1: one assembly point, and a second
                                                       handler wired the naive way reached it
                                                       unprompted. 2.2: every block is a
                                                       source literal. Deleting the builder
                                                       failed no test, so 2 and not 3.
                                                       (2.3 fact-only: first volatile byte at
                                                       offset 62)
tool boundary     tools/ToolRegistry:27         0      3.3: 2 of 14 tools take a URL parameter.
                                                       3.2: 9 distinct failure paths across 14
                                                       tools, 1 outcome shape — a 1 alone
discovery         tools/CatalogueLoader:55      0      4.1: booted green with an entry pointing
                                                       at a tool name that does not exist
guardrails        guard/InputChain:12           0      5.2: nothing on any path back to the
                                                       user, and truncation is silent. 5.3: the
                                                       classifier was made to throw and the
                                                       request was served, with nothing logged.
                                                       5.1: 12 checks, all on the inbound user
                                                       message — a 1 alone
memory            store/RedisChatStore:33       0      6.2: key is "chat:" + conversationId;
                                                       0 hits for a caller or tenant id in the
                                                       package
evals             absent                        0      §7 answer 1 is "no" — 0 test sources
                                                       call a real model, and a one-word
                                                       prompt change on a branch broke nothing
observability     obs/TurnListener:21           1      8.1: a 13th model call added by hand
                                                       emitted no span; tool calls reach
                                                       telemetry only through the error log.
                                                       8.6: two triggers — an inbound request,
                                                       and a nightly digest job whose spans do
                                                       carry a job id and a principal
sub-agents        n/a — no fan-out              —      no model call reachable from a tool or
                                                       from another agent's turn.
                                                       Trigger: the first fan-out, or the
                                                       first agent addressed over a wire
```

The two stamp lines are what make the next run's `diff` readable: they say which
tree these nine rows describe, so a 2 that has become a 1 is a demotion rather
than a different repository.

A time-boxed run renders the seams it never reached like this — never as 0, and
never blank:

```
discovery         not yet probed                —      out of budget; probe 4.1 goes first
                                                       next time
model access      agent/ClientFactory:41        —      1.1: one construction site, at startup.
                                                       Located, not scored — 1 vs 2 needs the
                                                       call site added and run
```

Two shapes, and the second is the one a short run gets wrong. **Where** holds the
symbol because locating it worked; **Score** is `—` because a paper reading
reaches 0 or nothing, and this seam's job exists. Had 1.1 found the client built
inside the handler, the same row would read 0 with no measurement needed.

Note the two rows that look alike and are not. **evals** is `absent`: the seam
applies to every codebase and nothing here does the job, so it scores 0 and it is
a finding. **sub-agents** is `n/a`: there is no gap in architecture that has none,
so it scores `—` and carries a trigger instead. Collapse the two and someone
counting zeros ranks a non-existent architecture beside a missing tenant key.

Note also that `absent` is the *only* row here whose 0 means "no code". Five
other rows score 0 while naming a real symbol, because a seam takes its lowest
job and one job at 0 is enough.

Read the columns strictly:

- **Where** is a `file:symbol`, or `absent`, or `n/a` plus its trigger, or `not
  yet probed`. This column is the only thing separating the last two — both score
  `—`, so a score never stands in for the distinction.
- **Score** is 0–3 off the ladder, and it is the seam's **lowest scored probe** —
  never an average, never the healthiest control. Model access, tool boundary and
  guardrails each hold a job that would be a 1 on its own beside a job at 0, and
  each row scores 0; the evidence names both, so the next auditor can see the
  rule bite rather than re-deriving it. A 2 means a call site added the naive way
  was covered unprompted — prompt assembly earned one that way, observability
  failed the same probe and sits at 1 — and a 3 means a named test went red when
  you deleted the control, or, at evals, that the merge was refused when you made
  the gate non-blocking. `n/a` and `not yet probed` score `—`, never 0 — and so
  does a seam a short run located and never measured, which is why the evidence,
  not the dash, says which of the three you are looking at.
- **Evidence** is probe output, prefixed with the probe number, and it leads with
  the probe that set the score: a count, a byte offset, a boot result, an empty
  search. Never a judgement about the code.

## 2. The ten coverage lines

The second block of the committed file, written straight off the table above —
item, seam, that seam's score, verdict. Nothing here is judged again; every
verdict is the score turned into one of four words by the rule in
`OWASP-COVERAGE.md`.

```
ASI01 Goal Hijack          guardrails 0        uncovered   also prompt assembly 2
ASI02 Tool Misuse          tool boundary 0     uncovered
ASI03 Identity & Privilege tool boundary 0     uncovered   also memory 0
ASI04 Supply Chain         discovery 0         uncovered
ASI05 Code Execution       tool boundary 0     uncovered
ASI06 Memory Poisoning     memory 0            uncovered
ASI07 Inter-Agent Comms    sub-agents n/a      n/a — until an agent is addressed
                                               over a wire
ASI08 Cascading Failures   sub-agents n/a      uncovered at the second seam:
                           model access 0      the SDK's 2 automatic retries are
                                               a retry loop nobody chose (1.4), so
                                               the trigger fires with no fan-out
                                               anywhere
ASI09 Human-Agent Trust    guardrails 0        uncovered — 5.2 a, b and c each
                                               answered "no"
ASI10 Rogue Agents         observability 1     uncovered — 1 is not covered; the
                                               nightly job at 8.6 fires the
                                               trigger. also evals 0
```

A time-boxed run writes the same ten lines. The seams it never reached carry the
fourth word, and the ones Step 1 located as `n/a` are unaffected by the budget:

```
ASI04 Supply Chain         discovery not yet probed  unmeasured — discovery not
                                                     yet probed; 4.1 goes first
                                                     next run
ASI07 Inter-Agent Comms    sub-agents n/a            n/a — until an agent is
                                                     addressed over a wire
ASI06 Memory Poisoning     memory 0                  uncovered — 6.2 alone; the
                                                     seam's other probes are
                                                     unrun and cannot lift a 0
```

Five things this block gets wrong when it is improvised:

- **Keyed on the identifier.** The words after `ASI04` are shorthand; published
  wording varies between sources, and `OWASP-COVERAGE.md` says why.
- **`n/a` is not a free pass.** ASI07 earns it — no sub-agents, no gap — and
  carries the trigger that ends it. ASI08 does not: its primary seam is `n/a` and
  its second seam is not, so the verdict comes from the second.
- **A 1 is uncovered.** ASI10 is the row everyone marks covered because
  observability exists and the score looks non-zero.
- **`unmeasured` is the short run's word, and its only one.** It belongs to a seam
  the table says was `not yet probed`; a seam that scored, even off one probe,
  takes the verdict its score earns.
- **Ten lines, no gaps.** A missing line is not a low-severity item, it is an
  unanswered question, and next quarter it reads as a clean bill — which is what a
  time-boxed run produces the moment it drops the three items it did not reach.

## 3. One finding block

Ranked, capped at ten. The example is rank 1 on the matrix: attacker-reachable,
irreversible, and silent on top of that.

```
FINDING 1 — Conversation memory has no tenant partition
Seam             memory
Score            0 → target 3
Previous         0 on 2025-12-05 — open across two audits
Evidence         probe 6.2 — the key is "chat:" + conversationId; 0 hits for a
                 caller or tenant id anywhere in the store package
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
Coverage         ASI06 uncovered (memory 0); ASI03 uncovered at the second seam
```

Three fields do the work everyone skips:

- **Evidence** keeps the argument about the codebase rather than about taste.
- **Smallest change** decides whether the plan is ever executed. Sized in files,
  it survives a planning meeting; written as "adopt a framework", it is still
  open next quarter.
- **Proved by** is what closes the finding. Without it the fix lands, and the
  seam is back at 1 the next time somebody adds a call site.

**Previous** reads `first run` when there is no committed table to read, and it
is the field that writes a finding you did not have to hunt for:

```
FINDING 6 — Telemetry no longer covers a new model call site
Seam             observability
Score            1 → target 2
Previous         2 on 2025-12-05 — demoted, and nothing was deleted to demote it:
                 a second call path was added beside the instrumented one
Evidence         probe 8.1 — a 13th model call added by hand emitted no span
```

A defect that lives in the gap between two seams — probe 4.5, where the tool set
is rebuilt each turn from a marker the store does not keep — names both, the one
that must change first in front. Not "the second seam", which the **Coverage**
field already uses for the OWASP map:

```
FINDING 3 — Skill activation is lost on every reload
Seam             discovery, then memory
Evidence         probe 4.5 with 6.1 — the marker survives the in-memory store and
                 is dropped by the real one; both seams pass their own probes
```

## 4. Ranking, worked

The matrix gives four ranks; ties inside a rank break on silence, then on how
often the path runs, then on the cost of the fix.

| Finding | Reach | Reversibility | Silent? | Runs | Rank |
|---|---|---|---|---|---|
| memory has no tenant partition | attacker | irreversible | yes | every turn | **1** |
| a tool parameter accepts a URL | attacker | irreversible | no — a blocked request fails loudly | on that tool | **1**, under memory: it fails loudly |
| tool calls visible only in the error log | internal | reversible | yes | every turn | **4** |

The third is real, cheap, and last. Ranking by cost if left, rather than by how
annoying the gap is to look at, is the whole point of the matrix.
