# The committed artefact

Open this at Step 6, when committing the three blocks, and at Step 1 of any run
that is not the first.

## The artefact, and when you run it again

The deliverable is one committed file at `docs/agentic-audit.md`, three blocks in
this order: the stamped nine-row scored table, the ten coverage lines, the capped
plan. Any of the three left out of the file is a run that produced it and threw it
away, and the next audit's `diff` never notices, because the block was never part
of the file's shape.

Same path every run, and committed, because an audit compounds only if the next
one can `diff` against the last: a seam that went from 2 to 1 is the cheapest
finding you will ever get, and nothing but the previous table can show it to you.
Another path is fine where the repository already has a home for documents — but
then Step 1 finds nothing at the default, so **commit a one-line pointer at
`docs/agentic-audit.md` naming the real path**, in the same commit as the report.
The stub is the mechanism and not a courtesy: run 2 opens that path and nothing
else, so a path recorded only in a summary or a commit message is a pointer with
no reader, and the diff degrades to a fresh one-off report — the whole failure
this page exists to prevent. One line, pointing at a table; a stub pointing at
a stub is the loop you get for free if nobody says otherwise.

**Stamp the table with the tree it was run against** — date, branch, commit — and
with the stamp of the run before it. A 2 that has become a 1 is only a finding if
you can tell it from an audit of a different tree; without the stamp the diff
shows two tables and no way to know whether the code moved or the auditor did.
`OUTPUT-SHAPES.md` carries the stamp line in the table head.

Re-run on a trigger, not when someone remembers: a tool is added or an existing
tool's authority widens; a framework or provider SDK major version; a new model
or a new provider; the first agent-to-agent hop, or the first unattended run.
