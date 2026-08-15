---
name: conversation-memory-and-compaction
description: Decide what a turn carries forward and what it may forget. Use when conversation history grows unbounded, when choosing between a message window and a token budget, when writing or reviewing a compaction or summarisation pass, when a long conversation silently changes what the agent can do, or when putting a cache in front of a conversation store.
---

# Conversation memory

The history **is** the prompt: the question is never "what still fits" but "what changes the answer"
— a bigger window is more RAM on a machine with a leak. Recall degrades with input length on every
model tested ([context rot](https://research.trychroma.com/context-rot)): mid-prompt facts are
answered 10–20% worse than at the edges, and accuracy on one multi-fact benchmark fell 0.92 → 0.68 by
3 000 tokens (Liu, TACL 2024; Levy, ACL 2024).

**Scope: one conversation's message list.** A cross-conversation profile inverts the default: inside
a conversation everything is in scope until dropped, across conversations nothing is until promoted
— so **promote only on an explicit act**, never a summariser's inference, and keep the turn it came
from, or a wrong "the user prefers X" is permanent and invisible.

## Window or token budget — the difference is reproducibility

**A message window** keeps the last N messages: bounded in count, unbounded in size, so one fat
tool result blows the prompt while the count still looks fine. **A token budget** keeps messages
until a size is reached: bounded in size, but its cut point moves with the tokenizer, so the same
stored history replays as a *different* prompt after an upgrade, where a window replays as a pure
function of the stored list. So **let the window decide what is evicted and a token budget decide
only when to compact**: eviction has to be replayable, a trigger only roughly right.

- **Pin the system message explicitly** — an eviction loop counting from the end takes it. If a
  budget drives eviction, store the **message list**, not the raw log alone, or replay is unservable.
- **Estimate the trigger, then calibrate the ratio against a real history — the error is
  directional.** Four characters per token is calibrated on English prose; punctuation-dense JSON
  runs nearer 2.5–3 (illustrative — count one real history against your provider's counter), so one
  flat ratio under-reads a tool-result-heavy history by a third and fires late on exactly the
  conversations that needed it early. Charge tool results the denser ratio.
- **A tool call and its result move together, both ways.** Half a pair is malformed history: some
  providers reject it, others hand the model a call with no outcome, which it fills in by guessing.

## What a turn may forget

Name these first — a compactor written as a list of prohibitions keeps everything.

| Forget | Why it carries nothing forward |
|---|---|
| a transient tool failure — timeout, rate limit, 5xx | no reusable fact, and three in a row teach the model the tool is broken, so it stops calling one that now works |
| an identical repeat of a result already present | fingerprint on tool name plus text; the second copy adds only position |
| the bulk of a large old result | load-bearing on the turn it arrived; later only its fact has to survive |

- **A determinate outcome is not a transient failure.** "That order number does not exist" is the
  fact that stops the model looking it up again — compress it to that sentence rather than drop it.
- **Key that row and the marker row below on a value your own code owns** — the outcome type
  `agentic-tool-boundary` returns instead of exceptions, a marker constant named in your own code —
  and pin each in a test: a rewording upstream otherwise kills the rule with the build still green.

## What a turn must remember: the invariant

**Loud loss:** the model asks the user to repeat the order number — one turn, and the user knows
something happened. **Silent loss:** behaviour changes and nothing says so; the model answers at the
same confidence from a state it should not be in. **The invariant is the state whose loss is silent**
— write that list before the compactor, because it is exactly what the compaction test asserts.

| Silent loss | Why nothing announces it |
|---|---|
| a correction — "no, the second one" | the value it rejects survives inside a summary wearing that summary's authority, and the message that rejected it survives pointing at a list that does not |
| a restriction the user imposed | losing a *granted* permission is loud, the agent asks again; losing a *withheld* one ("don't contact anyone about this") is silent, and the agent proceeds |
| a scope binding — which account, which document, which of two candidates | the model does not notice it has come unbound; it picks |
| a marker a framework reads back to reconstruct runtime state — an attribute carried on a stored message, `active_skill: billing`, scanned at load to rebuild which tools this turn can see | eviction losing it is expected, *compaction* losing it is a bug you authored, and the turn simply runs with a tool set nobody chose |

**A literal moves byte-identical; everything else moves resolved.** The marker row is a *literal* — a
token something scans for, and any rewrite of it is a miss. The other three rows are *bindings*: a
slot and its current value. What carries a binding forward is the resolved value, never the message
that set it. `"no, the second one"` kept verbatim is a pronoun whose list was summarised away — it
passes every string assertion and binds nothing.

**One slot, one value, the last setter wins.** Two corrections to the same slot are both invariants;
emitted verbatim in list order they arrive as two live bindings and the model picks between them —
often the first, being the one the block leads with. Key on the slot and overwrite, keeping the loser
as a *rejected* value, since "not that one" is what stops the model proposing it again. Recompute the
rejections against the current value rather than appending them, or a user who flips back to an
earlier choice gets `destination = Rio, rejected: [Rio, São Paulo]` — one line both binding a value
and forbidding it. And a slot re-bound *inside the anchor* is not emitted at all: the anchor is
authoritative, or a stale value sits in the summary slot outranking the live one.

**Finding a literal is a scan; resolving a binding needs the model**, so literals come out in the
cheap pass and bindings ride the summariser call, in a fixed shape rather than prose
([`COMPACTION-PASS.md`](COMPACTION-PASS.md)). When it cannot resolve a slot, that region stays
uncompacted — a confidently wrong binding costs more than a prompt that is still too long.

Those, plus a **recency anchor** — the last few messages untouched, so the immediate exchange
survives whatever else does not — define the partition:

```
BAD   summarise(everything older than the anchor)
GOOD  resolve the invariants out first; everything left over is a candidate

BEFORE  over the trigger, anchor = last 6   AFTER  system · summary · invariants · anchor
 0 sys  "You are…"                           0 sys  "You are…"                pinned
 1 usr  "Ship it to our office"              1 ast  "[summary] Shipping an order to a
 2 ast  call list_offices()                         branch office; two found; express
 3 tool {"offices":["São Paulo","Rio"]}             quoted R$ 40."
 4 ast  "Rio, as last time?"                 2 ast  "[state] destination = Rio (latest);
 5 usr  "no, São Paulo"         ← binds             São Paulo rejected"
 6 usr  "Any express option?"                3 usr  "Catalogue?"          ┐
 7 ast  call list_rates()                    4 ast  call list_catalogue() │
 8 tool {"rates":[…]} ← 9 200 chars          5 tool {"items":[…3 items…]} │ anchor,
 9 ast  "Express is R$ 40."                  6 ast  "Three items: …"      │ byte-
10 usr  "no, the second one"    ← rebinds    7 usr  "Add a gift note"     │ identical
11 usr  "Catalogue?"          ┐              8 ast  "What should it say?" ┘
12 ast  call list_catalogue() │
13 tool {"items":[…3 items…]} │ anchor
14 ast  "Three items: …"      │
15 usr  "Add a gift note"     │
16 ast  "What should it say?" ┘
```

Messages 1–10 are what the anchor does not protect (six is one deployment's anchor, not a constant).
Only message 3 says which office is the second, and message 3 is inside the summary — so message 10
kept verbatim is a sentence that resolves to nothing while passing any assertion looking for it. Keep
5 and 10 both and two destinations are live at once, the superseded one leading. One resolved line
carries the winner and the rejection, which a summary cannot: a summary asserts an address, never
that the user refused one, so a later turn re-resolving the destination has nothing to contradict.

**The test that proves it.** Build a history well over the trigger, bury the invariants at the oldest
end of the unprotected region with the anchor at its configured size, correct one slot twice, compact,
then assert per kind. Every literal byte-identical — a framework scanning for its marker will not find
it in prose. Every binding by *value*: ask the compacted history the question only the binding answers
("which office?") rather than string-matching the sentence that set it, or the assertion passes on a
preserved `"no, the second one"` and proves nothing. The superseded value present only as rejected.
And **prose is enough where the loss is embarrassing, never where it changes authorization** — "this
caller may not touch billing", as model-written text, is now negotiable.

## Compaction you should not build

For a twelve-turn conversation, or an agent whose whole job fits in a quarter of the trigger, **keep
everything** is the correct memory layer, and every compactor bug is one you never have to test for.

## The cheap pass runs before any model pass

Drop failed results, drop duplicates, lift the literals out, truncate the bulk of large results
*older than the anchor* — **marking each cut as compaction's own**. Re-check the budget: if that alone lands under it, the
turn cost no model call. Only what survives is summarised, then reassembled: system · summary ·
invariants · anchor. **Inside the anchor, the anchor wins:** those turns are still answering from it,
and a cut there reads as a result that came back short. If a 9 200-char result puts the anchor alone
over budget, shorten the anchor by whole call/result pairs; the fix is a cap at the tool boundary.

**Put the summary immediately after the system message** — the primacy slot; at the end it competes
with the recency anchor it is meant to predate. **That placement rewrites the prefix, so compact
rarely and largely.** `agentic-service-composition` owns why a rewritten prefix misses a provider's
cache and carries the rates; the arithmetic here is how often. At those rates — ~1.25× input to
write a prefix, ~0.1× to read it — compacting every third turn averages ~0.48× per turn against
~0.14× when one pass serves thirty, 3.5× the input bill for the same history. Watch the rate: twice in
five turns (illustrative) is a budget problem wearing a compaction success.

The pass runs at the end of a turn, uses the small model, and never throws. That placement, the race
it creates with the next turn, why its truncation mark must differ from the tool boundary's, the
summariser's own prompt, **the fields the bindings come back in**, what it must log, and **how the
trigger is calibrated** are in
[`COMPACTION-PASS.md`](COMPACTION-PASS.md) — where a 14 400-token trigger sits well above the 3 000
at which accuracy starts falling without contradicting it: that low, the lossy pass fires on nearly
every conversation.

## The summary is text you did not write

Input guardrails run at the invocation boundary; the summariser runs *outside* it and its output is
persisted, then replayed into every later prompt — so a tool result carrying planted instructions is
laundered into a durable, guardrail-free position by the one component whose job is "compress".
`prompt-injection-layers` requires a flagged message be *removed* from memory, not merely withheld
from the user — check which your framework does, because **withhold-only is often the default**.

Three controls on the summary itself, all placement rather than detection: **bound its length**, so a
compromised summariser cannot grow itself into the prompt; **keep its role non-system**, or it is now
standing instruction; and **never derive the standing prompt from memory** — configuration assembles
it at startup, and nothing in a conversation may change it. **ASI06, Memory & Context Poisoning.**

## A cache in front of the durable store

**A failed cache write deletes the key.** That rule's cost, write ordering, concurrent turns, the
in-process-store trap, TTL ordering and conversation-id validation are in [`STORAGE-TIER.md`](STORAGE-TIER.md).

## Reviewing an existing memory layer

0. **Measure the longest real conversation you have, in tokens.** If it never approaches the trigger,
   the compactor is a bug surface with no job — delete it, and questions 1–5 are moot.
1. What is the invariant list, split into literals and bindings? Does a test correct one slot twice
   and then assert the literals byte-identical and the bindings by value?
2. Can a tool call and its result be separated, by eviction or by compaction?
3. Does a model run before the deterministic pass, and is the compaction rate per conversation rising?
4. Is a guardrail-flagged message withheld or removed, and what role does the summary carry?
5. Fail the cache write: key deleted, or left stale? Can an unvalidated conversation id reach a key?

Over all of them: can you rebuild the exact prompt a past turn saw? If not, no eval over it replays.
