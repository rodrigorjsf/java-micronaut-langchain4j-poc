# Scoring a seam

Open this at Step 2, before writing the first score: the two halves of *nothing
strong lifts a row*, the two measurements that decide rungs 1 to 3, and which
probes change the system and must run on a scratch branch. The score table and the
job-not-seam rule are in `SKILL.md`, Step 2.

**Nothing strong lifts a row**, and that rule has two halves. Auditors inflate
this rung by applying only the first, because the seam looks built.

**Across jobs:** a job nothing does anywhere is 0 even where the seam has code and
**Where** names a real `file:symbol` — model access holding one shared client, one
model identifier and a boot check still scores 0 when nothing in the codebase
*chose* a bound on the call. **Inside one job:** the score is that job's weakest
path — eleven tools whose failures are red-tested and a twelfth returning raw
exceptions is a 1, because the score answers what the job *guarantees*.

Drop the across-jobs half and the seam is scored by its healthiest job; drop the
inside-one-job half and one good call site speaks for all of them. Either way two
audits of the same codebase stop comparing. Most 0s therefore name a real symbol;
`absent` is the one that does not.

Both measurements are run rather than read for, in ladder order:

**1 vs 2 — add a call site the way a newcomer would.** Copy the nearest existing
example, wire it the minimal way, change nothing else, run it, and write down
whether the control fired without you naming it. If you had to remember to call
something, it is 1. This is the rung nearly every *job that exists at all* lands
on, and reading the code decides it wrong every time: the correct call sites are
what you see, and the one you are scoring has not been written yet.

**2 vs 3 — delete the control locally and run the suite.** If nothing goes red
the score is 2, whatever the code looks like. Restore it, and record which test
you expected to fail. At the **evals** seam the control *is* the suite, so
deleting it to see a test go red is circular; §7 of `SEAM-PROBES.md` carries that
seam's four rungs, measured against the gate rather than against a test.

So 2 is not the finish line either. A seam at 2 decays to 1 the day it grows a
second door — a raw client kept beside the wrapper, a second registration route
for the one tool that did not fit the first. Nobody deletes anything, so the
change reads as an addition in review and as a demotion only in a diff against
your last table.

Several probes, and both measurements above, change the system to watch what it
does — breaking a configuration, failing a detector, deleting a control. Run them
on a scratch branch and revert; "report first, fix second" governs findings, not
probes. Skip them and you report those seams healthy, because the code looks
correct and only executing it disagrees.

**One probe a branch cannot undo.** Probe 3.8's cross-caller variant drives a live
tool with an id belonging to somebody else, and that read has already happened by
the time you switch branches back. Point it at a scratch tenant whose data is
yours, or at a stubbed downstream — never a real caller's id — the way 6.1 names
its scratch conversation. Its other two arguments are harmless: the hazard is that
one value, not the probe. Dropping 3.8 does not strip ASI02, because `OWASP-COVERAGE.md`
keys that item on three probes, and 3.3 and 3.6 still stand — you lose the one
clause about rejection at the boundary, and say so.
