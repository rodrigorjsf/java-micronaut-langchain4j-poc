---
name: conversation-memory-and-compaction
description: Decide what a turn carries forward and what it may forget. Use when conversation history grows unbounded, when choosing between a message window and a token budget, when writing or reviewing a compaction or summarisation pass, when a long conversation silently changes what the agent can do, or when putting a cache in front of a conversation store.
---

# Conversation memory

The history **is** the prompt, so the question is never "what still fits" — it is "what
changes the answer". Recall degrades with input length on every model tested, even on trivial
tasks ([Chroma, *context rot*](https://research.trychroma.com/context-rot)); mid-prompt facts
are answered 10–20% worse than the same facts at either edge (Liu et al., TACL 2024); on one
multi-fact benchmark accuracy fell 0.92 → 0.68 by 3 000 tokens of input (Levy et al., ACL
2024). A bigger window buys none of it back — it is more RAM on a machine with a leak.

**Scope: one conversation's message list.** A cross-conversation profile inverts the default —
inside a conversation everything is in scope until you drop it, across conversations nothing is
until you promote it, and a wrongly promoted "the user prefers X" is permanent and invisible.

## Window or token budget — the difference is reproducibility

**A message window** keeps the last N messages: bounded in count, unbounded in size, so one fat
tool result blows the prompt while the count still looks fine. **A token budget** keeps messages
until a size is reached: bounded in size, but its cut point moves with the tokenizer, so the same
stored history replays as a *different* prompt after an upgrade, where a window replays as a pure
function of the stored list. So **let the window decide what is evicted and a token budget decide
only when to compact**: eviction has to be replayable, a trigger only roughly right.

- **Pin the system message to the front explicitly** rather than trusting an eviction loop to
  spare it; and if a budget does drive eviction, store the resulting **message list**, not only
  the raw log, or "reproduce that turn" is unservable.
- **Estimate tokens for the trigger; do not tokenize** — the estimate chooses *when* to compact and
  never reaches an invoice, so a tokenizer sharpens a threshold past its own precision. But **the
  error is directional, so give it margin.** Four characters per token is calibrated on English
  prose; punctuation-dense JSON runs nearer 2.5–3 (illustrative — count one real history against
  your provider's counter). So it under-reads a tool-result-heavy history by roughly a third, and
  fires late on exactly the conversations that needed it early. Charge tool results the denser
  ratio, or set the trigger a measured margin under the ceiling you mean.
- **A tool call and its result move together, in both directions.** Half a pair is malformed
  history: some providers reject it outright, and the ones that accept it hand the model a call
  with no outcome, which it fills in by guessing.

## What a turn may forget

Name these first — a compactor written as a list of prohibitions keeps everything.

| Forget | Why it carries nothing forward |
|---|---|
| a transient tool failure — timeout, rate limit, 5xx | no reusable fact, and three in a row teach the model the tool is broken, so it stops calling one that now works |
| an identical repeat of a result already present | fingerprint on tool name plus text; the second copy adds only position |
| the bulk of a large old result | load-bearing on the turn it arrived; later it only has to stay identifiable |

- **Recognise failures by the outcome shapes the boundary emits** — the shaped values
  `agentic-tool-boundary` returns instead of exceptions — **pinned by a test.** Match on error text
  instead and a rewording upstream disables the rule silently.
- **A determinate outcome is not a transient failure.** "That order number does not exist" is the
  reusable fact that stops the model looking it up a second time — compress it to that sentence
  rather than dropping it.
- **Dropping a failed result means dropping its call too**, or you have built the malformed pair
  above from the other side.

## What a turn must remember: the invariant

**Loud loss:** the model asks the user to repeat the order number — one turn, and the user knows
something happened. **Silent loss:** behaviour changes and nothing says so; the model answers at
the same confidence from a state it should not be in. **The invariant is the state whose loss is
silent** — write that list before writing the compactor, because it is exactly what the compaction
test asserts.

| Silent loss | Why nothing announces it |
|---|---|
| a correction — "no, the second one" | summarise the exchange and the original wrong value survives wearing the authority of a summary, rather than of a message something later overrode |
| a restriction the user imposed | losing a *granted* permission is loud, the agent asks again; losing a *withheld* one ("don't contact anyone about this") is silent, and the agent proceeds |
| a scope binding — which account, which document, which of two candidates | the model does not notice it has come unbound; it picks |
| a marker a framework reads back to reconstruct runtime state — an attribute carried on a stored message, `active_skill: billing`, scanned at load to rebuild which tools this turn can see | eviction losing it is expected, *compaction* losing it is a bug you authored. **Pin the marker:** its constant is often the framework's, so it gets copied — a test asserting the copy still matches turns an upstream rename into a build failure, not a dead rule |

Those, plus a **recency anchor** — the last few messages untouched, so the immediate exchange
survives whatever else does not — define the partition:

```
BAD   summarise(everything older than the anchor)
GOOD  extract the invariants first; everything left over is a candidate

BEFORE (over budget; anchor = 6)       AFTER
 0 sys  "You are…"                     0 sys  "You are…"                 pinned
 1 usr  "Ship to the SP office"        1 asst "[summary] User asked to ship to
 2 ast  call get_address(sp-office)           the SP office; get_address returned
 3 tool {"street":"Av. Paulista 1000"}        Av. Paulista 1000."
 4 usr  "No — the Rio office"          2 usr  "No — the Rio office"  ← invariant
 5 ast  "Rio it is."                   3 usr  "What's in the catalogue?" ┐
 6 usr  "What's in the catalogue?"     4 ast  call list_catalogue()      │
 7 ast  call list_catalogue()          5 tool {"items":[…3 items…]}      │ anchor
 8 tool {"items":[…3 items…]}          6 ast  "Three items: …"           │
 9 ast  "Three items: …"               7 usr  "Add a gift note"          │
10 usr  "Add a gift note"              8 ast  "What should it say?"      ┘
11 ast  "What should it say?"
```

Messages 1–5 are what the anchor does not protect, and the correction sits inside them. Summarise
that region as a region and `Av. Paulista 1000` is the only address left in the record, asserted by
a summary — read as settled fact, not as a value something later overrode. Extraction is what pulls
message 4 back out, verbatim, past the summary. (Six is one deployment's anchor, not a constant.)

**The test that proves it.** Build a history several times over the trigger, put each invariant
deepest inside the region the anchor does not protect — never adjacent to it — compact, then assert
each survives *verbatim*, not as "a summary that mentions it": a framework scanning for its marker
will not find it in prose. And **prose is enough where the loss is embarrassing, never where it
changes authorization** — "this caller may not touch billing", surviving only as model-written text,
is negotiable by anything that can influence that text.

## Compaction you should not build

For a conversation averaging twelve turns, a single-turn classifier, or an agent whose whole job
fits in a quarter of the trigger, **keep everything** is the correct memory layer, and every
compactor bug is one you never have to test for. The trigger is then the entire design — question
zero of the review below is the measurement that decides it.

## The cheap pass runs before any model pass

Drop failed results, drop duplicates, truncate the bulk of large old results — **marking each cut
as compaction's own**. Then re-check the budget: when that alone lands under it, the turn cost no
model call at all. Only what survives is summarised, and the list is reassembled in one order:
system message · summary · invariants · anchor.

**Put the summary immediately after the system message** — the primacy slot; appended at the end
it competes with the recency anchor it is supposed to be older than. **That placement rewrites the
prefix, so compact rarely and largely.** `agentic-service-composition` owns why a rewritten prefix
stops a provider's cache from hitting; the consequence here is arithmetic. One large pass amortizes
that rebuild over many turns, while an incremental compactor firing every few turns can cost more
in re-billed input than the history it removed. A rising compaction rate means the budget is
mis-sized — twice in five turns is a budget problem wearing a compaction success as a disguise.

The pass runs at the end of a turn, uses the small model, and never throws. That placement, the
race it creates with the next turn, why compaction's truncation mark must differ from the tool
boundary's, the summariser's own prompt and what the pass must log are in
[`COMPACTION-PASS.md`](COMPACTION-PASS.md).

## The summary is text you did not write

Input guardrails run at the invocation boundary. The summariser runs *outside* it, on a background
pass, and its output is persisted and replayed into every later prompt — so a tool result carrying
planted instructions is laundered into a durable, guardrail-free position by the one component
whose job is "compress". The guardrail that caught the original on turn 4 does not run again on
turn 5; by then the text is already inside the history. `prompt-injection-layers` requires a flagged
message be *removed* from memory rather than merely withheld from the user — check which your
framework does, because **withhold-only is often the default**, and it leaves the conversation
poisoned while the health of the turn looks fine.

Three controls on the summary itself, all placement rather than detection: **bound its length**, so
a compromised summariser cannot grow itself into the prompt; **keep its role non-system**, because
a summary written back as a system message has been promoted to standing instruction; and **never
derive the standing prompt from memory** — it is assembled from configuration at startup, and
nothing a conversation contains may change it. Together these answer **ASI06, Memory & Context
Poisoning**.

## A cache in front of the durable store

**A failed cache write deletes the key.** That rule's cost, plus write ordering, concurrent turns,
the in-process-store trap, TTL ordering and conversation-id validation, is in
[`STORAGE-TIER.md`](STORAGE-TIER.md).

## Reviewing an existing memory layer

0. **First, measure the longest real conversation you have, in tokens.** If it never approaches the
   trigger, keep-everything is the correct layer here and the compactor is a bug surface with no
   job — delete it, and questions 1–5 are moot.
1. What is the invariant list, and does a test assert each item survives a deliberately hostile
   compaction — verbatim, not paraphrased into prose?
2. Can a tool call and its result be separated, by eviction or by compaction?
3. Does anything call a model before the deterministic pass has had its chance, and is the
   compaction rate per conversation flat or rising?
4. Where does a guardrail-flagged message end up — withheld, or removed — and what role is the
   summariser's own output written back as?
5. Make the cache write fail: is the key deleted, or left stale? And can a conversation id reach a
   storage key unvalidated?

Over all of them: given a stored conversation, can you rebuild the exact prompt some past turn saw?
If not, no eval over it replays.
