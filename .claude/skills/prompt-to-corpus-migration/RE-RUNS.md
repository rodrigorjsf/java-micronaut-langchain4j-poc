# Running the migration again

Open this when a ledger already exists: what run 2 reads first, how the closed
section works, and the check that runs in both directions.

## Running it again

**Read the ledger before you read the codebase**, and read the audit's prompt
assembly row next if there is one. Nothing at the ledger's path means this is run
1 and every **Previous** field reads `first run`.

**The settings block is the first thing you read and the first thing you compare.**
Read the six out of the codebase again and set them beside the recorded ones before
touching the inventory. A setting that moved changes what the already-closed
documents retrieve — a new segment size re-cuts every one of them — so the closed
section's acceptance suites re-run **before** a new block is ranked. That re-run is
`corpus-retrieval-tests`, which a model cannot load and this skill cannot execute:
ask the user to run corpus-retrieval-tests, which they invoke by name, and rank
nothing until its result is back. A run that
spends its cap on ten new migrations while the corpus it already owns retrieves
worse than it did last quarter is a net loss the ledger could have prevented.

**"Already migrated" is a row in the closed section**, carrying the date, the
document the block became, and the test that proves it. Closed rows are never
deleted: deleting them is how a fact walks back into the prompt with nobody able
to say it had ever left.

**The regression this pass exists to catch is new domain prose landing back in the
prompt**, because the prompt is the easy place to put it — no ingestion, no test,
no review of a document nobody owns. Detect it by diffing this run's inventory
against the last: a block that is new is either genuinely new content or a
re-import, and the closed section's subjects tell you which before you rank it.

### The check runs in both directions

`scripts/check-tool-catalogue.py` in this repository is the precedent, and its own
header says why the reverse direction earns its keep: a key in code and absent from
configuration "compiles, passes every unit test, and fails only when a user asks
the question that reaches it", while "a configured host nothing calls is an
allow-listed destination with no reason to be reachable". A corpus has both
failures:

- **prompt → corpus.** A fact present in both is the duplication `SKILL.md`, *The removal half*, opens on. Grep each
  closed row's subject against a freshly captured payload; a hit is a migration
  that did not finish.
- **corpus → prompt, the direction that is easy to skip.** A document the migration
  wrote that nothing routes to — embedded, correct, never retrieved, invisible
  because no test asks for it. **Every document in the corpus must be the intended
  chunk of at least one acceptance row** — `expected_source` in the query set. That
  is the same assertion `corpus-retrieval-tests` states as its document → row
  check, and the check itself is owned there, not specified a second time here: ask
  the user to run corpus-retrieval-tests, which they invoke by name, and record the
  result in the ledger.
