# The retrieval report and the diff mode

Open this to read a run, to find the rows a corpus edit puts at risk, or to give a
retrieval suite in another stack the same report. The query set's schema and the
four assertions stay in [`SKILL.md`](SKILL.md); this page covers only what a run
writes down about itself.

## Why a report and not only a green bar

An assertion says one bit per row. The report says what the bit hides: the score a
positive row cleared the threshold at (the rank-1-to-rank-3 drift `SKILL.md` warns
about is visible here a run before it goes red), the chunk a negative row should not
have retrieved, and the document nobody wrote a row for. A reader opens one file and
can say why a query failed without re-running anything.

## What the page holds

| Section | Per | Contents | The question it answers |
|---|---|---|---|
| header | run | threshold applied, top-k, `passed/total` | what configuration this run measured — read from the configuration the application ships, never a copy in the test |
| *Changed since `<ref>`* | changed document | the ids of the rows that depend on it, each with its current PASS/FAIL | which rows this edit put at risk (diff mode only) |
| *Queries* | query | id, question, expected document — or *nothing* for a negative — every retrieved chunk with source, score, title and text, threshold, PASS/FAIL | why this row passed or failed |
| *Coverage per document* | corpus document | positive rows naming it, how many pass | is every topic covered, and is its coverage green |
| *Uncovered documents* | corpus document | documents no positive row names | what shipped without a question proving it retrieves |

A corpus **topic** is one document here: it is the unit a positive row names in
`expected_source` and the unit an author adds or edits. Where your store addresses
chunks, the topic is still the document; the chunk is what the *Queries* section
shows.

**The pass rule, stated once.** A positive row passes when its `expected_source` is
among the retrieved chunks' sources; a negative row passes when nothing is
retrieved. Define it on the result type and have the assertion and the renderer both
call it — a report that computes its own verdict can disagree with the build.

**Self-contained.** Inline CSS, no script, nothing fetched from the network, every
question and chunk HTML-escaped (a chunk is corpus text and a question is user text;
neither is markup). The file opens from disk on any machine and is gitignored: it is
a run artefact, never history.

## Retrieve once per query

With an in-process embedding model the same question returns the same chunks every
time, so the suite retrieves each row **once**, before any assertion runs, and hands
the same results to the assertions and to the renderer. Two consequences worth
keeping: a failing row still reaches the report, because nothing threw before it was
recorded; and a filtered run (one test method) still writes every row. With a hosted
embedding model the same shape holds, and the pacing is `agentic-evals`'s.

## The diff mode

Given a git ref, list the corpus documents that differ between it and the working
tree — edited, added, deleted, renamed, untracked — and name, per document:

1. every positive row whose `expected_source` is that document; and
2. **every negative row.** Top-k is a competition and the threshold is absolute: an
   edit to any document can lift a question that used to retrieve nothing over the
   gate.

The list is the rows an edit touches **directly**, not every row it can move. A new
or edited document can also take the top-k slot another document's positive row
relied on; that row is not named here, and it does not need to be, because the next
section keeps the whole set running and the row goes red in *Queries*.

List a rename as a deletion plus an addition (`git diff --no-renames`). Otherwise
only the new name appears and the rows still pointing at the old one — the ones that
can now never pass — vanish from the list.

A changed document that names no positive row is a new or uncovered document: the
diff says so by listing only the negatives, and *Uncovered documents* lists it
again. Both are the same finding: write its row before it ships.

The diff mode selects nothing out of the run. Retrieval is cheap, so the whole set
still runs — which is what catches the displaced positive rows above — a regression lands on the row that *used to be* rank 3, which is rarely
a row the edit's author would have picked.

## In this repository

| What | Where |
|---|---|
| the query set | `src/test/resources/evals/retrieval-queries.json` — beside the other eval datasets, not beside the corpus: a test-resources `knowledge/` directory would shadow the corpus on the classpath and ingestion would read it instead |
| the test that retrieves once and asserts, in the default build | `src/test/java/.../rag/KnowledgeBaseTest.java` |
| the evals-profile run that retrieves once and writes the page | `src/test/java/.../rag/RetrievalReportEval.java`, both reading the rows through `RetrievalRun` |
| the pure renderer and the pass rule | `src/test/java/.../rag/RetrievalReport.java`, unit tested in `RetrievalReportTest` |
| the diff rule | `src/test/java/.../rag/CorpusDiff.java`, unit tested in `CorpusDiffTest` |
| the page | `retrieval-report.html` at the repository root, gitignored |

The assertions run in the default build — the embedding model is in-process — but the
page does not: it is written only in the evals profile, so `./mvnw verify` leaves the
working tree alone. The diff mode is one property:

```bash
./mvnw test -Pevals -Dtest=RetrievalReportEval                        # the report alone
./mvnw test -Pevals -Dtest=RetrievalReportEval -Dretrieval.diff=main  # plus the rows each document changed since main puts at risk
```
