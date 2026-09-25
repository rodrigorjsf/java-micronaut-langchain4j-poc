# When the run outlives the session

Open this when a loop will take longer than one sitting — a large build, a wide fan-out, anything
running against a quota that resets.

The failure is specific and total: **the loop dies with the session that started it**, and if the
work lived only in that session's context, it dies too. Rounds of building and every judgement made
about them are gone.

## Resumable by construction, not resumable after the fact

Many harnesses offer a resume that only works inside the session that started the run — which is
exactly the case that does not need it. Assume you have no resume, and build the loop so that
re-running it from scratch is cheap.

**1. Every path is stable.** Nothing that must survive lives in a session-scoped or temporary
location. A new session gets a new one of those, and it is empty.

**2. Every worker knows its own output path and checks it first.** The instruction is explicit:
*if your output file already exists and is complete, change nothing and return immediately.* A
re-run then costs one cheap check per finished step instead of redoing it.

**3. Every decision is written down, not only every artifact.** A verdict, a grade, a chosen
winner — these exist only in the run's own state unless something writes them out. Without them,
a later run cannot tell a finished step from an unstarted one and re-judges everything.

**4. Resume reads the most recent decision first.** A worker restarting must read the latest
verdict before the older instruction that spawned it, or it starts from the original draft and
discards a revision already made. State the precedence explicitly:

```
a passing grade recorded              -> change nothing
a failing grade, no fix wave started  -> run the fix wave on its findings
a fix wave partly applied             -> compare its changes with the wave's groups; finish the
                                         wave until its checks are green, then grade
work exists, no grade recorded        -> a round was cut off mid-judgement; grade the work as it
                                         stands, without revising it first
neither exists                        -> start from the beginning
```

The fourth row is the one that gets missed: re-running from the original throws the work away, and
revising before grading throws the measurement away.

## Resume from disk, not from memory

A resuming session — or the same one after its context was compacted — starts from a summary of
what happened, and a summary can be wrong: a run once reported as having changed nothing had
workers cut off mid-edit, their changes still in place. Before deciding anything, read the state
itself — what changed in the work since the last recorded state, which verdicts exist and what they
say, whether the checks pass — and let that overrule the summary. A worker the outage killed
returned no verdict; the trend rule on the main page says how to record it.

## Mirror while it runs

A run in progress holds its state wherever the harness put it, which may not be stable. A small
loop that copies artifacts and decisions to a stable location every minute or two costs almost
nothing, and it is what makes an unplanned stop a pause instead of a loss. It dies with the
session too — that is fine. Its job is to ensure that when the session dies, what it produced is
already somewhere else.

## Freeze deliberately when you can see the end coming

When a quota or a deadline is close, stop the run yourself rather than letting it be cut:

1. Stop after a round has recorded its verdicts, or after a fix wave's checks are green — never
   mid-wave, never mid-write.
2. Check every artifact is complete and well-formed — a truncated file is worse than a missing
   one, because a resume treats it as done.
3. Extract the decisions to their stable location.
4. Make everything durable wherever the work lives, whatever its grade.
5. Write the handoff.

## What the handoff must contain

Written for someone with **no memory of this run**, so that nothing is re-derived:

| Section | Because |
|---|---|
| Where the work stands, piece by piece, with its current grade | otherwise the next session re-judges what was already judged |
| The exact command or steps that resume, with no ambiguity about where they run | a resume that fails on a wrong path fails quietly |
| Which locations are durable and which were temporary and are now gone | otherwise the next session looks where the work was and finds nothing |
| What is still owed | a list that is not written down becomes a list nobody has |
| Every decision the human made about the bar, the scope and which paths count, word for word | every critic brief carries them; a critic never told reopens them |
| The trend table — for the coordinator, never for a critic brief | the next coordinator must see whether the loop converges; a critic that sees it learns the round count |
| **The conclusions that cost real work to reach** | measurements taken, approaches tried and rejected, a gate that was fixed and must not be reverted. This is the section that saves the most and is left out the most |

Keep it current as the work advances, and leave a pointer to it somewhere a fresh start will look
without being told.
