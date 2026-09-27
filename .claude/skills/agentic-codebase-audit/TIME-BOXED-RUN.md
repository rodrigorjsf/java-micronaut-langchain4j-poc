# A time-boxed run

Open this when the budget will not cover every probe at every seam. Step 1 still
runs in full; this page is how the rest of the budget is spent and what an unrun
probe writes.

## When you have two hours, not two days

A partial audit is a deliverable; an all-or-nothing procedure on a large codebase
produces nothing at all. **Step 1 still runs in full** — locating nine seams is
the cheap half, and it is what keeps the table nine rows of evidence rather than
five rows and four apologies. The budget is spent on Step 2, and it buys these
four seams, six probes, first: they are almost entirely source reads, needing no running
system beyond the one rider 5.1 names, and they are where absence is both most
common and most expensive:

1. **Guardrails, probe 5.1** — the census of every path carrying text the user
   did not type, and which of them has a check.
2. **Memory, probe 6.2** — the key builder and what partitions one caller from
   another.
3. **Tool boundary, probes 3.3 and 3.4** — the destination-parameter list, and
   the framework's default error handler read verbatim (in LangChain4j, what runs
   where `toolExecutionErrorHandler` is unset).
4. **Model access, probes 1.1 and 1.4** — the construction sites, and the bound
   this codebase chose on the call.

**A probe you did not run writes 0 or `—`, and nothing between.** 0
survives a paper reading because an empty answer has no rung beneath it — no bound
chosen anywhere, no check on any row of the census, no tenant component in the
key. Every rung above 0 is one of the two measurements in [`SCORING.md`](SCORING.md), so a job that turns
out to *exist* leaves that seam unscored: **Where** keeps the `file:symbol` you
located, **Score** reads `—`, and the evidence records the fact you established
and the rung nobody measured. Writing 1 because the code looks reasonable is
exactly the reading Step 2 says decides it wrong every time. The same rule covers
a seam whose probes you ran only some of: a 0 stands, because no unrun probe goes
lower, and anything above 0 waits.

The other five seams keep whatever Step 1 wrote in **Where** and score `—`: a
located seam is not an unprobed one, and erasing the `file:symbol` you just found
throws away the half of the audit you did finish. `not yet probed` is reserved
for a seam Step 1 itself never reached — which, on a budget that funds Step 1,
should be none. Commit **all three blocks**; a short run does not get to drop one.
The table is still nine rows. The coverage block is still ten lines: `uncovered`
where the mapped seam scored 0, and where the mapped seam carries no score,
`unmeasured — <seam> located at <file:symbol>, not measured` if Step 1 found it,
`unmeasured — <seam> not yet probed` only if it did not. `n/a` is a locate result
and stands whatever the budget was. The plan ranks what the six probes found,
and the summary names which seams you stopped at and which probe goes first next
time.
