# The compaction pass

Reference for [`conversation-memory-and-compaction`](SKILL.md): building the pass
itself, once you have decided a conversation needs one. *What* it may drop and what
it must never drop are in `SKILL.md`; this is the mechanics.

## Run it at the end of a turn

Run it after the user's answer has been sent. Run it before, and the user waits on a
summarisation call before their own answer even begins.

That creates a race: turn N+1 arrives while turn N's pass is still running, and
writing the compacted list back then deletes the new messages. Decide which list
wins — re-read and append, or discard the pass when the list changed underneath it —
and pin the decision with a two-concurrent-turn test.

## It never throws, and it records what it did

A failure leaves the conversation exactly as it was and increments a counter. A turn
that already succeeded must not fail while tidying up after itself.

Log the dropped message ids, the token count before and after, and the invariants
extracted — or keep the whole pre-compaction list. Without one of the two, a silent
loss in production is unreconstructable, and no eval can replay the prompt a past
turn actually saw.

## Mark a truncation as yours

**Truncating in history differs from truncating at ingress.** A tool boundary caps a
result *before* the model has ever seen it; compaction cuts one the model already read
and answered from. Mark which happened — `[truncated during compaction: older context]`
— or the model reads the ingress wording as a fresh result that came back short, and
calls the tool again to get the rest.

## The summariser's prompt

**Use the small model.** Compression is not reasoning — it is the class of model a
triage classifier runs on, several times faster than the agent's.

**Address the prompt to the assistant that will continue**, not to the user, or it
writes a customer-facing recap. Two instructions earn their tokens:

- keep each fact **with the tool that produced it**, so the next turn can tell an
  established fact from something the model once said;
- leave out raw JSON, URLs, error text and the user's exact wording — a summary that
  reproduces the transcript's tone has compressed nothing.

Give it one agreed word for "nothing worth carrying forward", and treat that word as
an empty summary rather than as an error.

## The invariant extractor rides the same call, and emits fields

The bindings come back from the summariser, not from the cheap pass — a
pronoun cannot be resolved by a scan — but they come back as **fields, not
sentences**: slot, current value, the message id that set it, and the values
rejected as of that value. Ask for a sentence and you get a summary again ("the
user seems to want the Rio office") — hedged, unkeyed, and impossible to overwrite
on the next pass.

The shape enforces the two rules for free: one entry per slot, so a later setter
overwrites instead of appending a second live value; and rejections recomputed
against the current value, so a user who flips back does not end up with their own
choice listed as rejected.

**Give it a way to say it could not resolve one.** Where the referent is ambiguous —
two candidates that read alike, a list it cannot index — the slot comes back
unresolved and the region holding it is left uncompacted this pass. The trigger
fires again next turn; a binding invented to fill the field is wrong for the rest of
the conversation, and nothing downstream can tell it was invented.

## Calibrating the trigger

From one deployment: a 14 400-token trigger, a six-message anchor, a 1 600-character
per-result cap. **Transfer the shape, not the numbers** — and do not expect to read a
trigger off a curve, because there is no knee to find. Accuracy falls continuously
with length, measurably by 3 000 tokens, so any trigger you pick is already past the
point where something was lost.

Measure both sides of the trade instead:

- **What length costs you.** Replay a graded set of turns against histories of
  growing length — 2 k, 8 k, 16 k, 32 k — and find where your task's accuracy leaves
  the band you are willing to ship.
- **What the pass costs you.** One summariser call per firing, a cache-invalidating
  prefix rewrite, and one more chance to drop state that no user will report.

Put the trigger where the first exceeds the second, and re-measure when the model or
the standing prompt changes — it is a property of both. Never where the context
window ends: that number is the provider's, and reaching it is a failure, not a
threshold.
