---
name: conversation-memory-and-compaction
description: Decide what a turn carries forward and what it may forget. Use when conversation history grows unbounded, when choosing between a message window and a token budget, when writing or reviewing a compaction or summarisation pass, when a long conversation silently changes what the agent can do, or when putting a cache in front of a conversation store.
---

# Conversation memory

The history **is** the prompt, so the question is never "what still fits" — it is
"what changes the answer".

Recall degrades with input length on every model tested, even on trivial tasks
([Chroma, *context rot*](https://research.trychroma.com/context-rot)). Mid-prompt
information is answered 10–20% worse than the same information at either edge (Liu
et al., TACL 2024); reasoning accuracy on one multi-fact benchmark fell 0.92 → 0.68
by 3 000 tokens of input (Levy et al., ACL 2024). A bigger window buys none of that
back; it is more RAM on a machine with a leak.

**Scope: one conversation's message list.** Promoting a fact out of it into a
profile spanning conversations inverts the default — inside a conversation
everything is in scope until you drop it, across conversations nothing is until you
promote it, and a wrongly promoted "the user prefers X" is permanent and invisible.

## Window or token budget — the difference is reproducibility

**A message window** keeps the last N messages: bounded in count, unbounded in
size, so one fat tool result blows the prompt while the count still looks fine. **A
token budget** keeps messages until a size is reached: bounded in size, but its cut
point moves with the tokenizer, the estimator, or the model.

| Policy | Rebuilding the prompt some past turn actually saw |
|---|---|
| message window | a pure function of the stored message list — replay an eval, reproduce a bug report |
| token budget | depends on the tokenizer in force at the time; the same history yields a different prompt after an upgrade |

So **let the window decide what is evicted and a token budget decide only when to
compact**: eviction has to be replayable, a trigger only roughly right. Pin the
system message to the front explicitly rather than trusting an eviction loop to
spare it. If a token budget does drive eviction, store the resulting **message
list**, not only the raw log, or "reproduce that turn" is unservable.

- **Estimate tokens for the trigger; do not tokenize.** Four characters per token
  chooses *when* to compact and never reaches an invoice. A tokenizer is a
  dependency and a per-turn cost bought to sharpen a threshold past its own precision.
- **Set the trigger where accuracy starts falling, not where the window ends.** On
  the figures above, a 200k-token window is not an argument for a 180k trigger.
- **A tool call and its result move together, in both directions.** Half a pair is
  a malformed history: some providers reject it outright, and the ones that accept
  it hand the model a call with no outcome, which it fills in by guessing. This
  binds eviction and compaction alike.

## What a turn may forget

Name these before naming what to keep — a compactor written as a list of
prohibitions keeps everything.

| Forget | Why it carries nothing forward |
|---|---|
| a transient tool failure — timeout, rate limit, 5xx | no reusable fact, and three in a row teach the model the tool is broken, so it stops calling one that now works |
| an identical repeat of a result already present | fingerprint on tool name plus text; the second copy adds only position |
| the bulk of a large old result | load-bearing on the turn it arrived; later it only has to stay identifiable |

- **Recognise failures by the outcome shapes your own tool boundary emits, pinned
  by a test.** Match on error text instead and a rewording disables the rule silently.
- **A determinate outcome is not a transient failure.** "That order number does not
  exist" is the reusable fact that stops the model looking it up a second time —
  compress it to that sentence rather than dropping it.
- **Dropping a failed result means dropping its call too.** A call left with no
  result is the malformed pair above, reached from the other side.

## What a turn must remember: the invariant

**Loud loss:** the model says "I don't have that order number, could you repeat
it?" and the user repeats it — one turn, and the user knows something happened.
**Silent loss:** behaviour changes and nothing says so; the model keeps answering,
at the same confidence, from a state it should not be in.

**The invariant is the state whose loss is silent.** Write that list before writing
the compactor — it is exactly what the compaction test asserts. Four kinds are
silent almost every time:

1. **A correction.** "No — the second one." Summarise the exchange and the original
   wrong value survives, wearing the authority of a summary rather than of a message
   something later overrode.
2. **A restriction the user imposed.** Losing a *granted* permission is loud — the
   agent asks again. Losing a *withheld* one ("don't contact anyone about this") is
   silent, and the agent proceeds.
3. **A scope binding** — which account, which document, which of two candidates was
   chosen. The model does not notice it has come unbound; it picks.
4. **A marker a framework reads back.** Some frameworks reconstruct runtime state —
   the visible tool set, an active mode — by scanning history for a marker message.
   Eviction losing it is expected; *compaction* losing it is a bug, because
   compaction is a rewrite you authored. **Pin the marker:** its identifying
   constant is often the framework's and sometimes
   package-private, so it gets copied — a test asserting the copy still matches
   turns an upstream rename into a build failure, not a silently disabled rule.

Those, plus a **recency anchor** — the last few messages untouched, so the immediate
exchange survives whatever else does not — define the partition:

```
BAD   summarise(everything older than the last six messages)
GOOD  extract the invariants first; everything left over is a candidate

BEFORE (over budget)              AFTER
0 system "You are…"               0 system "You are…"              pinned
1 user   "Ship to the SP office"  1 asst   "[summary] User asked to ship to the
2 asst   call get_address(…)                SP office; get_address returned
3 tool   {"cep":"01310-100",…}              Av. Paulista 1000."
4 user   "No — the Rio office"    2 user   "No — the Rio office"   ← invariant
5 asst   "Rio it is…"             3 asst   "Rio it is…"            ┐ anchor
6 user   "Add a gift note"        4 user   "Add a gift note"       ┘
                                  the call/result pair collapsed together, into
                                  a summary that took position one, not the end
```

**The test that proves it.** Build a history several times over the trigger, put
each invariant in the worst position — oldest, and buried mid-list — compact, then
assert each is present verbatim. Not "a summary that mentions it": a framework
scanning for a marker will not find it in prose. And **prose is enough where the
loss is embarrassing, never where it changes authorization** — "this caller may not
touch billing", surviving only as a bullet in model-written text, is negotiable by
anything that can influence that text.

## Compaction you should not build

A conversation averaging twelve turns, a single-turn classifier, an agent whose
whole job — standing prompt, tool schemas, one retrieved passage — fits in a quarter
of the trigger: for those, **keep everything** is the correct memory layer, and every
compactor bug is one you never have to test for. Measure the token count of your
longest real conversation before writing a summariser; if it never approaches the
trigger, the trigger is the entire design.

## The cheap pass runs before any model pass

```
over budget ─▶ drop failed results · drop duplicates · truncate large old results
           ─▶ under budget? ─yes─▶ done, no model call
           ─▶ no ─▶ summarise the candidates, then reassemble:
                    system message · summary · invariants · anchor
```

- **Run it at the end of a turn**, or the user waits on a summarisation call before
  their own answer begins. That creates a race: turn N+1 arrives while turn N's pass
  is still running, and writing the compacted list back then deletes the new
  messages. Decide which list wins — re-read and append, or discard the pass when
  the list changed under it — and pin it with a two-concurrent-turn test.
- **It never throws.** A failure leaves the conversation exactly as it was and
  increments a counter: a turn that already succeeded must not fail while tidying
  up. **Log what the pass did** — dropped message ids, token count before and after,
  invariants extracted — or a silent loss in production is unreconstructable and no
  eval can replay the prompt a past turn saw.
- **The model pass uses the small model.** Compression is not reasoning — the class
  of model a triage classifier runs on, several times faster than the agent's.

**Address the summarisation prompt to the assistant that will continue**, not to the
user, or it writes a customer-facing recap. Two instructions earn their tokens: keep
each fact *with the tool that produced it*, so the next turn can tell an established
fact from something the model once said; and leave out raw JSON, URLs, error text
and the user's exact wording — a summary that reproduces the transcript's tone has
compressed nothing. Give it one agreed word for "nothing worth carrying forward" and
treat that as an empty summary, not an error. Illustrative values from one
deployment: a 14 400-token trigger, a six-message anchor, a 1 600-character
per-result cap. Transfer the shape, not the numbers.

**Put the summary immediately after the system message** — the primacy slot.
Appended at the end it competes with the recency anchor it is supposed to be older
than. **That placement is a prefix rewrite, so compact rarely and largely.**
Providers cache a prompt *prefix*; rewriting position one invalidates the cached
prefix for the whole history, and the next turn pays full input price on all of it.
One large pass amortizes that rebuild over many turns, while an incremental
compactor firing every few turns can cost more than the history it removed. Watch
the compaction rate: rising means the window or the budget is mis-sized, and a
conversation compacting twice in five turns is a budget problem wearing a compaction
success as a disguise.

**Truncating in history differs from truncating at ingress.** A tool boundary caps a
result *before* the model has seen it; compaction cuts one the model already read
and answered from. Mark which happened — `[truncated during compaction: older
context]` — or the model reads the ingress wording as a fresh result that came back
short, and calls the tool again.

## The summary is text you did not write

Input guardrails run at the invocation boundary. The summariser runs *outside* it,
on a background pass, and its output is persisted and replayed into every later
prompt — so a tool result carrying planted instructions gets laundered into a
durable, guardrail-free position by a component whose job description is "compress".
The guardrail that caught the original on turn 4 does not run again on turn 5
either; by then the text is already inside the history. Removing a flagged message
from memory belongs to your injection layer; the part that belongs here is that
where a framework offers both outcomes, **withhold-only is often the default** — the
user is protected and the conversation stays poisoned.

Three controls on the summary itself, all placement rather than detection: **bound
its length**, so a compromised summariser cannot grow itself into the prompt; **keep
its role non-system**, because a summary written back as a system message has been
promoted to standing instruction; and **never derive the standing prompt from
memory** — it is assembled from configuration at startup, and nothing a conversation
contains may change it. Together these answer **ASI06, Memory & Context Poisoning**.

## A cache in front of the durable store

A conversation store with a cache in front carries its own rule set — write
ordering, what a failed cache write must do, two concurrent turns on one
conversation, availability asymmetry, and the conversation id as an authorization
boundary (**ASI03**). See [`STORAGE-TIER.md`](STORAGE-TIER.md).

## Reviewing an existing memory layer

1. What is the invariant list, and does a test assert each item survives a
   deliberately hostile compaction — verbatim, not paraphrased into prose?
2. Can a tool call and its result be separated, by eviction or by compaction?
3. Does anything call a model before the deterministic pass has had its chance, and
   is the compaction rate per conversation flat or rising?
4. Where does a guardrail-flagged message end up — withheld, or removed — and what
   role is the summariser's own output written back as?
5. Make the cache write fail: is the key deleted, or left stale? And can a
   conversation id reach a storage key unvalidated?

Over all five: given a stored conversation, can you rebuild the exact prompt some
past turn saw? If not, no eval over it replays.
