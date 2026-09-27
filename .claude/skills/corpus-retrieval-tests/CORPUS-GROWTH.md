# Re-running as the corpus grows

Open this when a new document lands in the corpus, or when a document is renamed.

## Re-running as the corpus grows

**Top-k is a competition, so a new document is a regression risk for old rows.**
The document under test is not where the failure lands; it lands on the row that
used to be rank 3. Two obligations follow.

A new document owes the query set: at least one positive row naming it, at least
one negative in its neighbourhood — the question next to it that it must not
answer — and **a full re-run of the whole set**, not just its own rows. The rank
recorded by assertion 1 is what makes the slow version of this visible: a row that
moved from rank 1 to rank 3 is a warning, and the run after next is where it goes
red.

**Then check both directions**, the habit this repository already runs at another
seam — `scripts/check-tool-catalogue.py` fails on a key present in the code and
absent from the configuration **and** on the reverse. Borrow the shape, not the
script:

- **document → row.** A document that is no positive row's `expected_source` is
  either dead weight or a missing row, and you cannot tell which without asking.
  In this checkout that direction holds: the six positive rows in
  `src/test/resources/evals/retrieval-queries.json` name all three files under
  `src/main/resources/knowledge/`, two rows each — and the report's *Uncovered
  documents* section is where it stops holding, the run it happens.
- **row → document.** An `expected_source` naming a file that no longer exists.
  After a rename the row can never pass, and the repair somebody reaches for under
  time pressure is relaxing the assertion — which is the failure `SKILL.md`, *Rows are not edited to make them pass*, names
  arriving through a door nobody was watching.
