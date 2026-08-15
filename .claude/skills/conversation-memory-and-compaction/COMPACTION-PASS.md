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

## Illustrative thresholds

From one deployment: a 14 400-token trigger, a six-message anchor, a 1 600-character
per-result cap. **Transfer the shape, not the numbers** — the trigger belongs where
accuracy starts falling for your model and your prompt, measured, not where the
context window happens to end.
