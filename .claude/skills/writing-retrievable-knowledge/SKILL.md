---
name: writing-retrievable-knowledge
description: Write a document so that one retrieved chunk of it answers the user alone. Use when adding a file to a retrieval corpus, when writing or rewriting a knowledge document, when a question that should match a document does not, when deciding how to break a document into headings and sections for a splitter, when a retrieved passage arrives meaning nothing on its own, or when the corpus is in the team's vocabulary and the users are not. For whether the content belongs in a corpus at all, its threshold and its router, use retrieval-that-earns-its-place; for content sitting in a prompt that should move, ask the user to run prompt-to-corpus-migration, and for proving the finished document actually retrieves, corpus-retrieval-tests — both of which they invoke by name.
---

# Writing a document that retrieves

The model never sees your document. It sees one segment of it — cut by a splitter
that does not know what a paragraph means to you — alone, with no heading above it
unless you put one there, beside two segments from other files. Every habit of
ordinary writing assumes a reader who arrived at the top of the page: a subject
introduced once, a table with a header, "as described above", a numbered list with
its stem. All of them break at a cut nobody can see while writing. **The chunk, not
the document, is the unit of retrieval**, and a document is well written here when
any one of its pieces, read alone, is worth retrieving.

| You are here because | Start at |
|---|---|
| you are about to write a new corpus document | *Read the splitter before you write a line* |
| a question that should match this document does not | *An embedding matches the user's words, not the team's*, then *The orphan chunk* |
| a retrieved passage arrived meaning nothing on its own | *The orphan chunk* |
| you are rewriting an existing document to retrieve better | [`WORKED-EXAMPLE.md`](WORKED-EXAMPLE.md) |
| the document carries a table, a numbered list or a code sample | *Structures that do not survive a cut* |
| you are not sure this content belongs in a corpus at all | `retrieval-that-earns-its-place` — that decision comes before this page |
| this content is currently in a system prompt | ask the user to run `prompt-to-corpus-migration`, which they invoke by name — audit and plan first, write second |

This page decides nothing about retrieval configuration. It **reads** the
configuration and writes against it. Choosing the threshold or the router belongs
to `retrieval-that-earns-its-place`.

## Read the splitter before you write a line

Writing without the splitter's numbers is writing to an imaginary page size.

**Find it in the ingestion path, not in the config file.** Search where the corpus
is loaded and written to the store for `split`, `chunk_size`, `chunk_overlap`,
`chunkSize`, `SEGMENT`, `TextSplitter`, `node_parser`. Three places it hides:

- **passed at the call site**, as literals or constants next to the ingest loop;
- **not passed at all** — the framework's default applies. Read the default *for the
  version in the lock file* and write it down; an unstated default is still the size
  you are writing against;
- **outside the repository entirely** — a hosted ingestion pipeline or a managed
  vector service where the number lives in a console. Then no file in the project
  answers the question and you go and read the service's setting. Guessing here is
  how a corpus gets written to 1,000 characters and ingested at 250.

(On a migration run — one the user starts, since `prompt-to-corpus-migration` is not
a skill a model can load — these numbers are already in that skill's ledger. Take
them from there rather than reading them twice, and still settle the unit question
below, which a ledger entry may not record.)

**Ask which unit it counts.** `512` in one framework is tokens; `600` in another is
characters. English prose is commonly quoted at roughly four characters per token —
a rule of thumb, not a measurement — so a writer who assumes the wrong unit is
mis-sized by about that factor in one direction or the other. Settle it by reading
the parameter's documentation for that version, or by ingesting one document and
printing the length of a produced chunk in both units.

Then read what each number changes about the writing:

| What you read | What it changes about the writing |
|---|---|
| segment size | the budget for one self-contained answer. A section that must be understood whole may not exceed it |
| overlap | whether a fact sitting on a boundary survives in one of the two halves. It is insurance against a bad cut, never a licence to write across one |
| the strategy — structural/recursive vs fixed-length | structural: it *prefers* paragraph and heading boundaries, so where you put a heading decides where a cut can land — heading discipline is a retrieval decision and not a typographic one. That is also why a heading may arrive without its text: set off by a blank line it is a paragraph of its own, so it is packed onto the tail of the previous segment whenever there is room. Never rely on it travelling with the paragraph under it. Fixed-length: cuts land mid-sentence, and every section must be short enough to be one chunk |
| top-k — how many chunks the answer is assembled from | whether an answer may be spread across sections, or must live inside one |
| whether metadata is embedded | whether a heading kept only in metadata contributes to matching at all — see *Metadata* below |

One worked example of the reasoning, not a default to copy: in this repository
`rag/KnowledgeBase.java` splits at **600 characters with 100 of overlap**, because
that is "small enough that a retrieved segment is mostly signal, large enough to
keep a heading with the paragraph under it", with the overlap there "so a fact split
across a boundary survives in one of the two halves". Those numbers were chosen for
short Portuguese Markdown read by a quantized MiniLM.

**Budget arithmetic.** A document of `L` units splits into at least
`ceil((L - overlap) / (size - overlap))` chunks — for this repository's largest
knowledge file, 1,817 characters (`wc -m`, this checkout), `ceil((1817 - 100) / 500)`
= **4**. That is a floor and not a count, since a structural splitter ends a segment
early at a boundary rather than filling it. And the count is not the comparison that
decides anything: **the one that does is whether any single section exceeds the
segment budget.** If one does it will be cut, and the only open question is whether
you chose the cut or the splitter did.

## The orphan chunk

A paragraph whose subject is "it", named two headings above, is a chunk that
retrieves and means nothing. It scores well, arrives in the prompt, and the model
has to answer from a passage that never says what it is about.

```
BAD   ## Renewal
      It happens automatically at the end of the term, unless the conditions
      described above apply.
GOOD  ## Renewing a loan
      A loan renews automatically on its due date, unless somebody has reserved
      the same title or the account is over the fine limit.
```

Three instructions, all positive:

- **Repeat the subject noun** in the first sentence of every section, however
  clumsy that reads to somebody holding the whole page. The whole page is not what
  gets retrieved.
- **Resolve every pronoun that crosses a heading.** Inside a paragraph "it" is
  fine; across a heading it is a dangling reference to a chunk that will not be
  there.
- **Follow no cross-reference a chunk cannot follow.** "As described above", "see
  the previous section", "the second case" are all instructions to a reader who has
  the page. Either restate the one sentence the section actually needs, or name the
  destination in words a person could search for.

The test is mechanical: **delete everything above the section and read what is
left.** If you could not say what it is about, neither can the model.

**Repeat the subject, not the explanation.** Pushed one step too far, this rule
produces two sections that restate the same explanation in slightly different
words. They then match the same question, both score well, and both occupy a
top-k slot — so an answer assembled from three chunks is built from fewer distinct
facts than three. Each section carries the subject noun *and one fact the others do
not have*. Where a second section genuinely needs the first one's rule, restate the
single sentence it needs and nothing else.

→ [`WORKED-EXAMPLE.md`](WORKED-EXAMPLE.md) — open when rewriting a section that does
not retrieve: one badly written section in a neutral domain, the chunk it produced,
the five writing defects named one at a time, and the rewrite.

## One question per section, answered in the first sentence

**Write the heading as the thing a user asks.** "Renewal policy" is the team's
filing label; "Can I renew a book and keep it longer?" is the query. A heading in
the interrogative also makes the section's scope obvious to whoever reviews it.

Whether the heading helps at all depends on the splitter: check that the heading
actually travels into the chunk with the paragraph under it, by printing one
produced chunk and looking. If it does not, the heading's words must be repeated in
the first sentence, which is where they were needed anyway.

**Front-load the answer.** The first sentence carries the section in the embedding
more than any later one, and a section that opens with preamble matches preamble.

```
BAD   Our circulation system offers a number of options for patrons who need
      more time with an item.
GOOD  A loan renews for another three weeks, automatically, on its due date.
```

**One question per section.** A section that answers three questions matches all
three weakly and answers whichever one the retrieved half happens to contain. Split
it into three sections, each with its own subject sentence — and accept the
repetition of the subject noun that follows, because that is the point.

## An embedding matches the user's words, not the team's

The corpus is written by people who know the system, in the vocabulary they use
with each other. The query is typed by somebody who does not. If the document says
"circulation period" and the user types "how long can I keep it", nothing anchors,
and the failure is silent: a top hit with a mediocre score, or nothing above the
threshold, and no line anywhere saying the words did not meet.

Put the user's phrasing into the document on purpose:

- the **common wrong term** — what people call the thing when they have it slightly
  wrong;
- the **abbreviation** and the expansion, both, at least once;
- the **terse form** somebody types at speed, without punctuation or a verb;
- the **question itself**, as a heading or as a sentence in the section.

**Where those words come from is one priority-ordered list, and
`corpus-retrieval-tests` owns it.** Read it there rather than from a second copy on
this page: two copies drift, and the one you would be reading is the wrong one. What
this page needs from that list is only the property that makes it work — the wording
is somebody else's.

Where this project has none of the sources that list names, write the questions
yourself and then have
somebody who has not read the document write twenty more. Questions written by the
document's author reuse the document's vocabulary and match by construction — a
rigged green, the same one `authoring-agent-skills` warns about for skill
descriptions.

**There is one question set, it has one home, and it has one owner.** It is committed
beside the corpus, and `corpus-retrieval-tests` owns its schema. You build it here to
know what to write and to check coverage in both directions — or, on a migration run,
you extend the acceptance questions `prompt-to-corpus-migration` already wrote into
that same set, rather than starting a second list nobody reconciles. Later,
`corpus-retrieval-tests` turns each question into a row — the question, the intended
chunk (`expected_source` in the query set) and the negatives — and owns the diagnosis
when a row fails. Commit the questions with the document in this pass; do not write
assertions.

## Structures that do not survive a cut

| Structure | What a cut does to it | Write instead |
|---|---|---|
| a long table | the header row stays in the first chunk; the second chunk is rows of unlabelled cells | keep a table inside one segment. A long one becomes several short tables under their own headings, or sentences — "code 95 means a thunderstorm" survives alone, a row reading `95 \| thunderstorm` does not |
| a numbered list with a stem | "To transfer a hold, do the following:" stays behind, and the chunk opens at "4." with nothing saying what four is for | put the stem in the heading as well as above the list, and write each item as a sentence naming its own subject |
| a code or config sample | it splits mid-token, and half a JSON object retrieves as text nobody can act on | keep the sample small enough to fit whole with the sentence explaining it. A long one belongs in the repository, described here by what it does |
| a document-wide qualifier — "all times below are UTC" | it applies to every section and travels with none | state the qualifier in each section that depends on it |

## Rich and complete, defined so padding cannot satisfy it

"Rich" invites volume, and volume is the one thing that makes retrieval worse: more
text, more near-duplicate chunks, more ways to match weakly. Use two criteria
instead, both checkable by a reviewer.

**Coverage, checked in both directions.** Every question on the list maps to at
least one section, and every section maps to at least one question. A section no
question reaches is padding — delete it or find the question it answers. A question
no section reaches is the gap you were meant to find. This repository already runs
that shape at a different seam: `scripts/check-tool-catalogue.py` fails when a key
exists in the code and not in the configuration **and** when it exists in the
configuration and not in the code. Borrow the shape, not the script; the
one-directional version passes happily while half the ground is missing.

**Every claim traceable to something a reviewer can check.** A corpus document is
documentation that a human reviews in a pull request, and that review is the only
thing keeping it true. So it may not contain a fact nobody owns: each claim names a
field the API returns, a limit in the configuration, a rule in the code, a published
policy. If you cannot say what makes a sentence true, go and find out or do not
write it.

Depth is not a third criterion; it is what the first two produce once the question
list is honest — a section answers the follow-up as well as the question — the exception, the failure, the
"why". A model given only the happy path invents the exception. And prefer the shape
of a number the code owns ("at most a few dozen results") over the number itself,
unless the document and the code are read from the same place; a copied constant
drifts on the first release nobody thought to re-read this file.

## Metadata: what travels, and what is matched

Two different questions get confused into one. What **travels with the chunk** is
what makes attribution and filtering possible, and `retrieval-that-earns-its-place`
covers why to have it. What is **embedded** is a
separate setting, and it is the one that changes your writing: if metadata is not
part of the embedded text, a heading kept only in metadata contributes nothing to
matching, and every word that must match has to be in the chunk's own text.

**Determine which your framework does; do not assume.** Read the ingest path for
what is actually passed to the embedding call, or ingest two chunks with identical
text and different metadata and score a query that matches only the metadata — an
unchanged score means metadata is not embedded.

What the embedded case costs: metadata repeated on every chunk dilutes the text's
own signal, since the same few words compete with the sentence they were meant to
help — which is why a heading that matters belongs in the prose whatever the setting
turns out to be.

## Where this sits in the order of work

**Three skills, four steps, one order, and it does not commute.**

| Step | Skill | What it produces |
|---|---|---|
| audit and plan | `prompt-to-corpus-migration` — ask the user to run it, which they invoke by name | content in the wrong place, and where each piece goes |
| write the document | this page | the document, and the question set beside it |
| prove it answers | `corpus-retrieval-tests` — ask the user to run it, which they invoke by name | rows that pass, and a diagnosis when they do not |
| delete the text from the prompt | `prompt-to-corpus-migration` again — the user's run, not yours | a smaller prompt — **only after the rows pass** |

**This page is the only one of the four steps a model can start.**
`prompt-to-corpus-migration` and `corpus-retrieval-tests` both carry
`disable-model-invocation: true`, so nothing a model emits reaches either of them.
Steps 1, 3 and 4 happen when the user runs those skills by name — a model that
reports one of them done has reported something it could not have done.

Deleting the prompt text before the rows pass is how a capability disappears with
nothing red. And some content is never migrated at all: a rule that applies to
*every* answer buys nothing from routing and fails silently when the turn is not
recognised. That decision is `prompt-to-corpus-migration`'s, not this page's.

## Reviewing a document before it is embedded, and what done means

Run in order; done is the right-hand column.

| # | Check | Done when |
|---|---|---|
| 1 | Read the splitter configuration in this project | you can state the segment size, the overlap, the **unit** it counts and whether it cuts at structure — from the ingest path, the versioned default, or the hosted service's own setting, never from an assumption |
| 2 | Measure each section against the segment budget | no section that must be understood whole exceeds it, or it has been split at a boundary **you** chose |
| 3 | Delete everything above each section and read it alone | the subject noun is named in the section, no pronoun points outside it, and no cross-reference asks the chunk to follow something it cannot |
| 4 | Read only the first sentence of every section | it answers the question, and it contains words a user would actually type |
| 5 | Read the sections against each other | no two restate the same explanation; each carries a distinct fact under its repeated subject |
| 6 | Check the question set against the document in both directions | every question reaches a section and every section is reached by a question; the set is committed beside the corpus, in the schema `corpus-retrieval-tests` owns |
| 7 | Ask where the question wording came from | some of it came from the sources named in `corpus-retrieval-tests`' priority-ordered list — turns written by somebody who did not write the document — and not only from you |
| 8 | Find every table, list, code sample and document-wide qualifier | each fits inside one segment with its header, stem or explanation, or has been rewritten as sentences that stand alone |
| 9 | Ask of each claim what makes it true | each names a checkable thing in the system; nothing in the document is a fact nobody owns |
| 10 | Settle whether metadata is embedded here | settled by reading the ingest path or by the two-chunk comparison, and every word that must match is in the chunk text regardless |
| 11 | Ask the user to run `corpus-retrieval-tests`, which they invoke by name | a positive row exists for each question, naming the intended chunk as its `expected_source`, and passes. Reading the document is not evidence that it retrieves; this row is the only one that closes the work, and it is not a row a model can close from here |
