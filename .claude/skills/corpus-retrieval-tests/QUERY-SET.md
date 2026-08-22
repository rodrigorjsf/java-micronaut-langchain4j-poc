# A query set, worked

Open this when writing the first rows, or when a field in
[`SKILL.md`](SKILL.md)'s row table needs a shape. The domain is invented and
neutral — a public library's assistant, the same one
`writing-retrievable-knowledge`'s `WORKED-EXAMPLE.md` uses — with a corpus of four documents: `renewals.md`, `fines.md`, `holds.md`,
`hours-and-branches.md`. Two facts about it decide several rows below: the library
serves an English- and Spanish-speaking membership, so the corpus carries both
languages and so must the query set; and the assistant has a tool that looks up a
specific copy's availability, which is why one negative row exists at all. A triage
classifier runs before the agent and produces a capability hint, which is the
router's signal — `none` means it named no capability, and in this design that is
what makes the turn retrieve.

## Two rows in full

The on-disk form is whatever your repository already reads — CSV, TSV, JSON,
YAML. What matters is that it is data, in a stable order, committed beside the
corpus. One positive and one negative, with every field filled:

```yaml
- id: renew-001
  question: "can I keep this book longer or do I have to bring it back"
  label: positive
  expected_source: renewals.md
  routing_input: none          # the triage classifier named no capability
  variant_of: null
  families: [coverage]
  provenance: "support inbox, ticket 4471, 2026-02-11"

- id: neg-availability-001
  question: "is the second copy of the Hobbit on the shelf right now"
  label: negative
  expected_empty: router-declined
  routing_input: "capability: copy-availability"
  variant_of: null
  families: [tool-question]
  provenance: "walked the tool catalogue: this is what the availability tool serves"
```

`neg-availability-001` is the kind of negative row the whole suite turns on. It is
in the product's own vocabulary, it is a question the assistant answers well — and
it must answer it **with a tool**, not with prose about how availability works. It
asserts a specific emptiness: the router declined, so the threshold was never
consulted.

## The set

Twelve rows over four documents. `variant_of` is shown as the base row's id.

| id | question | label | expects | routing_input | variant_of |
|---|---|---|---|---|---|
| `renew-001` | can I keep this book longer or do I have to bring it back | positive | `renewals.md` | none | — |
| `renew-002` | puedo quedarme con el libro mas tiempo | positive | `renewals.md` | none | `renew-001` |
| `renew-003` | renew loan | positive | `renewals.md` | none | `renew-001` |
| `renew-004` | can I renwe if someone reserved it | positive | `renewals.md` | none | `renew-001` |
| `fines-001` | what happens if I bring a book back late | positive | `fines.md` | none | — |
| `fines-002` | how much is the daily fee and is there a cap | positive | `fines.md` | none | — |
| `holds-001` | somebody else reserved the book I have, what now | positive | `holds.md` | none | — |
| `hours-001` | what time does the east branch close on a sunday | positive | `hours-and-branches.md` | none | — |
| `neg-availability-001` | is the second copy of the Hobbit on the shelf right now | negative | router-declined | `capability: copy-availability` | — |
| `neg-neighbour-001` | can I get a library card if I live in the next county | negative | routed-nothing-cleared | none | — |
| `neg-deleted-001` | how do I book a study room for four people | negative | routed-nothing-cleared | none | — |
| `neg-same-noun-001` | renew my membership card | negative | routed-nothing-cleared | none | — |

Read the negative half as four different assertions rather than four synonyms for
"no":

- `neg-availability-001` — **the tool's question.** Every tool in the catalogue
  generates one of these.
- `neg-neighbour-001` — **the neighbouring area.** Membership eligibility is next
  to the corpus and deliberately outside it. If `hours-and-branches.md` starts
  matching this, a section there has widened its claim.
- `neg-deleted-001` — **the deleted section.** Study rooms were in the corpus and
  were removed. This row fails when a stale chunk survives an edit or the store was
  not rebuilt.
- `neg-same-noun-001` — **same noun, other intent.** *Renew* is `renewals.md`'s own
  subject word, used in a request the corpus does not serve. A failure here says
  the embedding is keying on the noun rather than on the question, and that is the
  row most likely to break on an embedding-model change.

Three of the eight positives are variants — `renew-002` through `renew-004` — and
each names a layer it can break: the Spanish the library serves, the terse typed
form, and a misspelling. The misspelling row also carries the `evasion` family, so
`agentic-evals`'s rule applies and it is asserted individually rather than averaged
in.

`fines-002` is **not** a variant, although it shares a document with `fines-001`. A
second question-intent against the same section is a base row, and that is the axis
which actually scales a corpus suite. A fourth renewal variant reading *"could you
please tell me whether I may keep this book longer"* is in neither category and is
not in the file at all: its content words are `renew-001`'s, so nothing about it
can fail alone.

## The four assertions, in prose

Framework-neutral, in the order [`SKILL.md`](SKILL.md) lists them. Every one runs
through the retriever the application runs, with the row's `routing_input`, at the
shipped top-k and threshold — except the third, which cannot.

**1. Positive rows.** For each, retrieve with the application's own path and
collect the source metadata of every returned chunk. Assert the row's
`expected_source` is among them. Record the rank it appeared at and its score, and
print both on failure **and on success** — the rank is the early warning that a new
document is taking the slot. A row that returns nothing and a row that returns the
wrong document are different failures; say which in the message.

**2. Negative rows.** For each, retrieve the same way and assert nothing came back.
Then assert the *manner*: for `router-declined`, that the router returned no
retriever for that `routing_input`; for `routed-nothing-cleared`, that the router
routed and every candidate fell below the threshold. A bare emptiness assertion
passes in both cases, including the case where the router declined for a reason
nobody intended.

**3. The distribution.** One assertion for the whole file, and the only one that
runs with the gate open: build a second retriever over the same store and embedding
model with no minimum score and k of 1, take the top score for every positive
question and every negative question, and assert the relationship you measured —
whether the lowest positive score sits above the highest negative one. A flip in
either direction is the finding: separated distributions mean the threshold can do
the job alone and the router may be deletable; a wider overlap means the opposite.
Name both outcomes in the assertion message, because whoever sees it go red will
not have this page open.

**4. Attribution.** For any positive row, assert every returned chunk carries the
source and title metadata the answer cites. Assertion 1 reads that same field, so
when it is missing this fails first and says why.

## What the file does not contain

**No expected answer text.** A row that pins the model's wording turns a retrieval
regression into a diff of prose and puts a judge into a suite that needed none.
What the answer does with retrieved chunks is `agentic-evals`'s ground.

**No thresholds, and no top-k.** Those are the application's configuration, read at
run time by the test. A copy in the query set drifts from the shipped value, and
then the suite is green about a system nobody runs.

## The same file, on a hosted embedding API

Nothing about the rows changes. What changes is where the suite runs: tagged out of
the default build, run before a release, and paced under a measured rate limit —
`agentic-evals` owns that split, and its `RUNNING-A-SUITE.md` owns the pacing,
the `ran / total` block and what a skipped row means. A quota error on
`neg-neighbour-001` is not a passing negative row; it is a skipped one, and
counting it as a pass is the most expensive arithmetic error in the file.
