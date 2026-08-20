# Choosing a bar when nothing obvious exists

Open this when the work has no artifact to be measured against — a novel feature, an internal
document, a decision, a story nobody has written before.

The temptation is to fall back on an adjective, and the loop then fails in the way the main page
describes: the critic reconstructs the standard each round, and it drifts.

## Four sources, in the order worth trying

**1. The best instance you can find of the same kind of thing.** Not the same subject — the same
*kind*. A runbook for a service you are not running is still a runbook: it shows the density, the
ordering, what it assumes the reader knows. A competitor's onboarding is a bar for yours.

**2. Your own best previous work.** "As good as the chapter we already shipped" is fetchable,
comparable, and has the advantage that its voice is one you can actually match. This is the
strongest bar for anything internal, and the most commonly overlooked.

**3. A specification, read clause by clause.** An RFC, a schema, an accessibility standard, an
acceptance criterion someone already wrote. The comparison is mechanical: for each clause, does
the work satisfy it, with the clause number beside the verdict. Weaker than a real artifact — a
spec says what must not be wrong, rarely what would be good — but never drifts.

**4. The rejected alternative.** When there is genuinely nothing to compare against, build two
versions independently and compare *those*. The bar becomes "better than the other attempt at
this", which is fetchable by construction. You lose the absolute standard and keep the blinding,
which is most of the value.

## Making an unfetchable bar fetchable

A bar in someone's head becomes usable the moment it is written down as something checkable.

```
BAD    "it should feel fast"
GOOD   "first paint under 1s on a throttled 3G profile, screenshotted with the timing overlay"

BAD    "the API should be intuitive"
GOOD   "a developer who has not seen it writes the three most common calls from the reference
        page alone, without opening the source"

BAD    "the story should be engaging"
GOOD   "the opening two pages of <a named book in this genre> — same rate of concrete detail,
        same distance between the reader and the events"
```

Each GOOD line is longer, and each one a critic can act on identically twice.

## What to do about the parts a bar cannot reach

Most real work has an aspect no reference captures — a constraint from your domain, a decision
already made elsewhere, something that must be true for reasons outside the artifact.

Keep those **separate from the bar**, as a short list of conditions the work must satisfy
regardless of how the comparison goes. The critic checks them independently and reports them
independently. Folding them into the bar makes the comparison muddy: the work loses on
"engagement" for reasons that were actually about a constraint, and nobody can tell which.
