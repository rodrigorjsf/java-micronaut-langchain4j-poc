# Running a fix wave

Open this when a round came back with blocking findings. A round measures; the wave, between
rounds, closes what it found — and every fix is new text the next critic will read.

## Red or filed

Every blocking finding becomes, **before anything is edited**, one of:

- **red** — a mechanical check that fails at the defect: a test, a checker rule. Aim it at the
  *class*, not the reported line; a predicate finds the twins the critic did not name. A check that
  already passes on a phrase present for another reason proves nothing — tighten it until it fails
  for the right reason.
- **filed** — a tracked issue with the reproducing state, when no check can fail for the right
  reason; say why in the issue.

Filing replaces the check, not the fix: the defect is still fixed in this wave, unless the human
decides to defer it — and that decision goes into every later critic's brief.

## Disjoint owners

Split the fixes into groups of files that do not overlap, one fixer each. A rule that spans groups
gets **one home and exact wording**, written into a shared brief before anyone starts — the home's
location, the sentence other places use to point at it, the one fixer who writes the home. Fixers
who improvise the same rule separately disagree.

The check suite and any generated copy belong to the coordinator. Before anything is kept, compare
each fixer's changes with its group; a change outside it goes back to the owner.

## The wave gets its own critic

An adversarial review whose whole task is to **break** the wave, under the same brief rules as a
round's critic (it sees the work, never the fixer's reasoning), with the doubts the fixers reported
about their own work. It runs until a cycle finds nothing blocking, or for two cycles; what is open
after the second is filed, not looped on.

Blockers that come from the wave's own design — new surface rather than missed sites — are a signal
to rethink the design, and often to take a simpler alternative to the human.

## Prefer the fix that deletes

A rule added to close one path is a new path to attack. The shorter artifact has fewer places for
the next defect to hide; when two fixes close the same finding, take the one that removes text.

## Done when

Every blocking finding is red or filed, and fixed unless the human deferred it; the fixers' changes
stayed inside their groups; the review ended clean or after two cycles with the rest filed; the
checks are green; the changes are durable. Then the next round grades the current work.
