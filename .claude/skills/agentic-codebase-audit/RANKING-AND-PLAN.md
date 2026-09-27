# Ranking the findings and writing the plan

Open this at Step 4, once every seam carries a score or the run has stopped
probing: the two axes that rank a finding, and the fields every plan entry carries.

### Step 4 — Blast radius

Severity labels do not rank. Two axes do, both answerable from probe output.

**Reach** — can something an attacker controls get to this seam: their text, or
an identifier they supply? Uploaded documents, retrieved pages, tool responses
and sub-agent replies are attacker-writable in some deployment; name which.

**Reversibility** — of the **incident the gap permits**, not of the gap itself. A
missing error taxonomy is not "irreversible"; the wasted retries it causes are
reversible, and the cross-tenant read a missing partition key permits is not.
Classify the incident, or two auditors score the same finding differently.

| | Irreversible incident | Reversible incident |
|---|---|---|
| **Attacker-reachable** | rank 1 | rank 2 |
| **Internal only** | rank 3 | rank 4 |

Inside a rank, in this order: **silent before loud**, then every-request before
rare-path, then cheapest fix first. Silence is the tie-break that matters — a
seam that fails loudly gets fixed by whoever is on call; a seam that fails
silently is still failing a year later, and the first person to notice is a
customer.

### Step 5 — The plan

Every finding names its seam, its score and target, **the score this seam carried
in the previous audit**, the probe output it came from, its two rank inputs and
whether it fails silently, what it costs if it is left, the **smallest change**
that closes it, the test that **proves** it closed, and the coverage items it
moves.

Two entries in that list exist for findings that otherwise have no shape to be
written in. **A seam that fell** — 2 last run, 1 now — is the cheapest finding in
the file, and only the previous score carries it; `first run` is the honest entry
when there is nothing to compare against. **A defect that spans two seams** — a
disclosure marker the store silently drops, probe 4.5, invisible in either seam
alone — names both in the seam field, the one that must change first in front,
because filed against a single seam it lands on a team that cannot close it.

An unscored seam still produces findings, the way a fact-only probe does: the
score field reads `— → target 2`, and the evidence carries the fact instead of a
rung. Probe 1.1 finding one construction site at startup, with nobody having
measured whether a new call site reaches it, is a real plan entry whose smallest
change is the measurement itself — one call site added the naive way, run once.
Dropping those because the seam has no number is how a short run becomes no run.

**Smallest change is a constraint, not a courtesy.** An audit that recommends a
rewrite gets filed, and the seam stays at 0 for another year. Size it in files
touched, and split any finding whose fix is bigger than one change into the part
that stops the bleeding and the part that does it properly.

**Cap the plan at ten findings.** Ten that get done beat thirty that get read.
The rest stay in the scored table, where the next audit will find them.
