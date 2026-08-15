# What the audit produces

Two shapes: the scored table, and one block per finding. Both are filled in below
against an invented codebase, so the columns are unambiguous. Copy the shapes,
not the contents.

## The scored table

Nine rows, always. This is the head of the committed file, and the object the
next audit diffs against.

```
Seam              Where                         Score  Evidence
────────────────────────────────────────────────────────────────────────────────
model access      agent/ClientFactory:41        1      1 construction site, inside the request
                                                       handler; 0 hits for a timeout setting,
                                                       so the SDK default (10 min) applies
prompt assembly   agent/PromptBuilder:18        2      single assembly point; block 3 is a
                                                       database row editable without a deploy;
                                                       first volatile byte at offset 62 (a
                                                       rendered date), so almost none of the
                                                       prefix is cached from turn to turn
tool boundary     tools/ToolRegistry:27         1      14 tools, 9 distinct failure paths, 1
                                                       outcome shape; 2 parameters accept a URL
discovery         tools/CatalogueLoader:55      0      booted green with an entry pointing at
                                                       a tool name that does not exist
guardrails        guard/InputChain:12           1      12 checks, all inbound; 0 on any path
                                                       back to the user; truncation is silent
memory            store/RedisChatStore:33       0      key is "chat:" + conversationId; 0 hits
                                                       for a caller or tenant id in the package
evals             absent                        0      0 test sources call a real model.
                                                       Trigger: already applicable
observability     obs/TurnListener:21           2      request id spans every model call; tool
                                                       outcomes absent from telemetry; the bill
                                                       is one number
sub-agents        absent                        0      no model call reachable from a tool.
                                                       Trigger: the first fan-out, or the
                                                       first agent addressed over a wire
```

Read the columns strictly:

- **Where** is a `file:symbol`, or `absent`, or `not yet probed`. `absent` means
  the seam does not exist, and carries the trigger that will make it applicable.
  `not yet probed` means you ran out of budget. They are opposite claims.
- **Score** is 0–3 off the ladder, and a 3 means a named test went red when you
  deleted the control. A `not yet probed` seam scores `—`, never 0.
- **Evidence** is probe output: a count, a byte offset, a boot result, an empty
  search. Never a judgement about the code.

## One finding block

Ranked, capped at ten. The example is rank 1 on the matrix: attacker-reachable,
irreversible, and silent on top of that.

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
| the bill is one number | internal | reversible | yes | always | **4** |

The third is real, cheap, and last. Ranking by cost if left, rather than by how
annoying the gap is to look at, is the whole point of the matrix.
