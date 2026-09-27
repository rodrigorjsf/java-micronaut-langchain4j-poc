# The retrieval settings, read before writing

Open this before planning any document: the six settings the corpus is written
against, where each one hides, and why they are the ledger's first committed
block.

## Read the retrieval configuration first

The chunker decides the shape of what you write, so reading it after the plan
produces a plan measured in blocks and a corpus measured in chunks, with no
correspondence between them. Six settings, and in every stack they live somewhere
different:

| Read | Where it hides | What it decides |
|---|---|---|
| segment size and overlap | as often a constant beside the ingestion loop as a config file | whether a block is one document or five, and where a heading may go |
| the embedding model, and whether it runs in-process | the store's construction, usually a model id string | which vocabulary matches, and what re-embedding costs when you edit |
| top-k | config | how many chunks compete to be the answer |
| the score threshold | config | whether a correct chunk that scores just under counts as retrieved at all |
| whether a router exists, and on which signal | there may be none — then retrieval runs on every turn, and *is* standing text | whether question 2 of `SKILL.md`, *The test for what may move*, is answerable in this system |
| what metadata rides each chunk | the ingestion loop | whether an answer can cite its source |

**The six are a committed block, not a scratch note.** They are the ledger's
first block, at the stable path — one line each, carrying the value with its
unit where it has one, the file it was read from, and the value the previous run
recorded — because the documents you are about to write are written against
them. A segment size that moves from 800 to 1,200 between runs re-chunks every
document already migrated, and a ledger that never held the old number cannot
tell you it moved: you read 1,200 on run 2, find it plausible, and the corpus is
mis-chunked with nothing red. The record is also what
`writing-retrievable-knowledge` reads on a migration run, rather than deriving
the same numbers a second time out of the same files.

**A default nobody set is still a decision.** Where you find no size, no overlap
and no threshold, the library's defaults are in force — go and read them in its
documentation and write them into that block, with the library, its version from
the lock file, and the page you read as the provenance. An unstated default is the
value most likely to change under you on a minor version bump.

One repository's values, as an illustration of what these entries look like when
found — **not** as numbers to copy. Measured on one small Portuguese corpus (three
Markdown files, 118 lines by `wc -l`) with a quantized MiniLM embedding model:
`rag/KnowledgeBase.java` splits at 600-character segments with 100 of overlap, and
its comment says why — "Small enough that a retrieved segment is mostly signal,
large enough to keep a heading with the paragraph under it. The overlap exists so
a fact split across a boundary survives in one of the two halves." Retrieval in
`application.yml` under `agentic.rag` reads `max-results: 3`, `min-score: 0.72`.
Different corpus, different language, different model, different numbers.

Whether a threshold separates relevant questions from irrelevant ones is
**measured, not assumed** — on that corpus the distributions overlapped, which is
why a router exists at all. The measurement and what to do when it fails to
separate are `retrieval-that-earns-its-place`'s; do not re-derive them here. What
this skill takes from it is one consequence: a proof gate may never read "the
score looked high".

This document stops at the configuration. **How the splitter cuts, what makes a
chunk survive alone, and how a document is worded so a user's phrasing matches it
are `writing-retrievable-knowledge`'s**, and that is the next thing you open.
