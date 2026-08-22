# What the migration produces

The committed file has four blocks in this order — the settings block, the
inventory table, the closed section, the ranked plan — and this file works all
four. They are filled in against **one invented codebase**, so a row in one block
can be traced into the next. Copy the shapes, not the contents; every number below
is illustrative and none of it was measured on anything.

The shapes come from `agentic-codebase-audit`'s `OUTPUT-SHAPES.md` and are reused
deliberately, so a reader who has seen one file can read the other: the two stamp
lines at the head, an **Evidence** column carrying captured output rather than
judgement, a **Previous** field with `first run` as its honest empty value — reused
here as a whole column of the settings block — a **Smallest change** sized in
files, and a **Proved by** field naming the test that closes the entry. Four shapes
are new here because the noun is different: the settings block, the byte-and-share
columns of the inventory, the closed section, and the plan entry's **Acceptance
list** field. That last one is a pointer, not a list: the questions themselves are
rows in the one query set committed beside the corpus, whose schema
`corpus-retrieval-tests` owns, and this file neither defines them nor gives them a
second home.

## 1. The settings block

The six settings of *Read the retrieval configuration first*, one line each, at the
head of the committed file under the stamp lines. This is what the next run reads
before anything else, and the **Previous** column is the whole reason it is
committed rather than kept in a scratch buffer.

```
migration 2026-05-02 · branch main · commit 9c14ba7
previous  2026-02-11 · commit 3ad0e51

Setting               This run                        Read from                    Previous
──────────────────────────────────────────────────────────────────────────────────────────────
segment size /        1,200 chars / 150 chars         ingest/split.py:22           800 / 150
  overlap                                                                          — SIZE CHANGED
embedding model       text-embedding-3-small, hosted  ingest/store.py:9            same
top-k                 4                               config/rag.yaml              3 — CHANGED
score threshold       0.55                            config/rag.yaml              0.55
router                yes — on the triage verdict's   router/Route.py:31           yes, same signal
                      capability hint
chunk metadata        source path + heading           ingest/split.py:38           source path only
```

- **Numbers carry their unit.** `512` is tokens in one library and characters in
  another, and a ledger line that records only the digits hands the next writer the
  wrong size with the authority of a committed file.
- **Read from** is a `file:line`, or the library and its lock-file version for a
  number nobody set — `langchain-text-splitters 0.3.4, default chunk_overlap=200,
  API docs` is a provenance; `default` alone is not.
- **Previous** is where the block earns its place. `SIZE CHANGED` on the first line
  says every document in the closed section — `docs/corpus/shipping.md` and
  `docs/corpus/warranty.md`, both written against 800 — is now cut at 1,200, with
  its headings landing somewhere else. Their acceptance suites re-run before this
  run ranks anything new. Without the recorded 800, run 2 reads 1,200 out of
  `split.py`, finds it plausible, and the corpus is mis-chunked with nothing red.
  `top-k` moved as well and the same re-run answers it — which is why the rule is
  *any* setting that moved, not this one.
- On run 1 every **Previous** cell reads `first run`, and the block is still
  committed. It is worth nothing this run and everything the next one.

## 2. The inventory table

One row per block found in the captured payload, directly under the settings block
and sharing its stamp lines.

```
Block                     Origin                        B      U       Verdict   Evidence
──────────────────────────────────────────────────────────────────────────────────────────
brand voice contract      prompt/Assembly.tone:14       1,180  1.00    stays     Q1 yes — constrains the form of
                                                                                 every answer. Kept deliberately;
                                                                                 see the plan's note
refusal + escalation      prompt/Assembly.policy:31     860    1.00    stays     Q1 yes, and its failure is silent
output contract (JSON)    prompt/Assembly.format:44     410    1.00    stays     Q1 yes
plan catalogue            prompt/blocks/plans.txt       6,240  unknown moves     Q2: "the user asks what a plan
  (7 tiers, prices,                                                              includes". Signal exists — the
  limits)                                                                        triage verdict already names a
                                                                                 capability. Q3: invents — quoted a
                                                                                 tier that was withdrawn in March
field glossary            prompt/blocks/glossary.txt    3,905  unknown moves     Q2 sentence written. Q3: says it
                                                                                 does not know
tool-choice guidance      prompt/Assembly.tools:52      780    —       neither   D: duplicates search_orders' own
                                                                                 description verbatim — delete
                                                                                 here, fix there
few-shot: 3 examples      prompt/Assembly.shots:60      2,110  —       split     S: 2 teach the JSON shape, 1
                                                                                 teaches the refund window. Cut
                                                                                 below; this row is not ranked
  ├ few-shot: JSON shape  prompt/Assembly.shots:60      1,400  1.00    stays     Q1 yes — teaches the form of
                                                                                 every answer
  └ few-shot: refund      prompt/Assembly.shots:60      710    unknown moves     Q2: "the user asks whether an
      window                                                                     item can still be returned". Q3:
                                                                                 invents a 60-day window; the
                                                                                 policy says 30
(unattributed)            —                             1,430  —       —         no grep hit; framework's own
                                                                                 tool-schema preamble. Named, not
                                                                                 blank
```

Read the columns strictly:

- **Origin** is a `file:symbol`, a file path where the block *is* the file, or
  `—` for the unattributed remainder. An unattributed remainder is a real row: it
  is the part of the payload you have not explained, and deleting the row is how a
  prompt with a second assembly point passes a clean inventory.
- **B** is bytes measured out of the captured payload, never out of the source —
  the source has not been interpolated yet.
- **U** is a share you can defend or the literal `unknown`. `unknown` is the
  common and honest entry; a number here that nobody measured is what gets the
  plan thrown out.
- **Verdict** is one of the four closed values — `stays`, `moves`, `split`,
  `neither` — and evidence names the check that decided it: `S`, `D`, `Q1`, `Q2`
  or `Q3`. Next run needs to know whether a `stays` was a form rule (`Q1`) or a
  missing signal (`Q2`), because the two decay differently: a form rule stays
  forever, a missing signal ends the day somebody builds the signal.
- **Evidence** is captured output and the question number. Never "this looks like
  it belongs in the corpus".

**A `split` parent keeps its row and gains its children indented beneath it.** The
parent holds the full byte count and no verdict past `split`; each child carries
its own `B`, `U` and verdict, and only children are ranked — `1,400 + 710 = 2,110`,
so the halves account for the parent and nothing was mislaid in the cut. Keeping
the parent is what lets the next run recognise the block if it is re-imported whole.

Three rows here are the ones an improvised table loses. `neither` is not `stays` —
nobody re-examines a `stays` row, and this one wants deleting today. A `split`
parent is not a verdict, it is a row that had to become two before either half
could be ranked. And the unattributed remainder is not a rounding error at 1,430
bytes of a payload you are about to argue about.

## 3. The closed section

Migrated blocks move here and are never deleted. This is the only record that a
fact was ever in the prompt, and it is what run 3 greps a new block's subject
against before ranking it as new content.

```
Closed                Became                          Proved by                       Closed on
──────────────────────────────────────────────────────────────────────────────────────────────
shipping zones (4,110 B)  docs/corpus/shipping.md     CorpusRetrievalTest#zones —     2026-02-11
                                                      6 acceptance rows, routed        commit 3ad0e51
warranty policy (2,300 B) docs/corpus/warranty.md     CorpusRetrievalTest#warranty —   2026-02-11
                                                      4 rows, routed                   commit 3ad0e51
regional tax notes        —                           —                                2026-02-11
  (1,900 B)                                                                            returned to stays:
                                                                                       Q2 failed in practice —
                                                                                       nothing distinguishes a
                                                                                       turn that needs it
```

The third row is the one worth writing down. A block that came back is a finished
piece of work, not an abandoned one, and without this line run 3 spends its budget
re-deciding it and reaches the same answer. `returned to stays` names the question
that turned it back; that is what makes the entry re-openable when the signal it
needed gets built.

## 4. One plan entry

Ranked by waste and volatility, tie-broken on silence, capped at ten.

```
MIGRATION 1 — Plan catalogue ships on every turn and is three months stale
Block            plan catalogue — prompt/blocks/plans.txt
Verdict          moves — Q2 signal: the triage verdict already names a capability
Previous         first run — not in the 2026-02-11 inventory
Evidence         6,240 B of the captured payload, C = 1.00, U unknown. Q3 probe:
                 with the block removed on a scratch branch the model quoted the
                 "Studio" tier, withdrawn in March — it invents rather than
                 declining
Waste            6,240 B on every turn; U unknown, so ranked on B alone
Volatility       high — pricing changed twice since the block was written, and
                 neither change reached this file
Silence          silent — a withdrawn tier is quoted in a confident sentence and
                 nothing logs it
Acceptance list  7 questions in users' words, committed before the document
                 exists as rows in the query set beside the corpus — the one
                 whose schema corpus-retrieval-tests owns; no second file
Smallest change  one corpus document, one capability line left in the prompt,
                 the block deleted — 3 files, in 3 commits
Proved by        CorpusRetrievalTest#plans — every acceptance row retrieves its
                 intended chunk (expected_source) through the router at the
                 shipped threshold
Re-measured      pending step 4: fresh payload must not contain the block
```

Four fields do the work an improvised entry drops:

- **Acceptance list** is committed before the document. Written after, its
  questions come out of the document's own vocabulary and every one passes. It is
  **not** a new file at a path this skill invents: the rows go into the one query
  set committed beside the corpus, `corpus-retrieval-tests` owns its schema, and
  each row names the intended chunk in that schema's `expected_source` field.
- **Smallest change** is sized in files *and* in commits, because the deletion
  being its own commit is what makes the migration revertable without also
  reverting the corpus.
- **Proved by** names the test — the one `corpus-retrieval-tests` builds, which a
  model cannot start: ask the user to run corpus-retrieval-tests, which they invoke
  by name. Without this field the document lands, the block is deleted and nothing
  notices when a later edit stops the chunk retrieving.
- **Re-measured** stays `pending` until a payload captured *after* the deletion has
  been read. A migration whose last field never left `pending` is the one that only
  added.
