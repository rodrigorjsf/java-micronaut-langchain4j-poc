# What the audit produces

Two shapes: the scored table, and one block per finding. Both are filled in below
against an invented codebase, so the columns are unambiguous. Copy the shapes,
not the contents.

## The scored table

Nine rows, always. This is the head of the committed file, at the stable path the
next audit diffs against.

```
audit 2026-03-14 · branch main · commit 4f2a1c9
previous 2025-12-05 · commit 7e11b03

Seam              Where                         Score  Evidence
────────────────────────────────────────────────────────────────────────────────
model access      agent/ClientFactory:41        0      1.4: no timeout and no connection cap
                                                       set anywhere, so the bound is whatever
                                                       the SDK defaults to and nobody picked
                                                       it. 1.1: the one construction site sits
                                                       inside the request handler — a 1 alone
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
                                                       user, and truncation is silent. 5.1: 12
                                                       checks, all on the inbound user message
                                                       — a 1 alone
memory            store/RedisChatStore:33       0      6.2: key is "chat:" + conversationId;
                                                       0 hits for a caller or tenant id in the
                                                       package
evals             absent                        0      §7 answer 1 is "no" — 0 test sources
                                                       call a real model, and a one-word
                                                       prompt change on a branch broke nothing
observability     obs/TurnListener:21           1      8.1: a 13th model call added by hand
                                                       emitted no span; tool calls reach
                                                       telemetry only through the error log
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
```

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
  the gate non-blocking. `n/a` and `not yet probed` score `—`, never 0.
- **Evidence** is probe output, prefixed with the probe number, and it leads with
  the probe that set the score: a count, a byte offset, a boot result, an empty
  search. Never a judgement about the code.

## One finding block

Ranked, capped at ten. The example is rank 1 on the matrix: attacker-reachable,
irreversible, and silent on top of that.

```
FINDING 1 — Conversation memory has no tenant partition
Seam             memory
Score            0 → target 3
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

## Ranking, worked

The matrix gives four ranks; ties inside a rank break on silence, then on how
often the path runs, then on the cost of the fix.

| Finding | Reach | Reversibility | Silent? | Runs | Rank |
|---|---|---|---|---|---|
| memory has no tenant partition | attacker | irreversible | yes | every turn | **1** |
| a tool parameter accepts a URL | attacker | irreversible | no — a blocked request fails loudly | on that tool | **1**, under memory: it fails loudly |
| tool calls visible only in the error log | internal | reversible | yes | every turn | **4** |

The third is real, cheap, and last. Ranking by cost if left, rather than by how
annoying the gap is to look at, is the whole point of the matrix.
