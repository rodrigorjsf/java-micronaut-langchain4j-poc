# Where the sentences come from

Open this when you need more questions than you have, or when a sibling skill
points at this skill's priority-ordered list of sources. This list is the only
one; the layers it names are defined in `SKILL.md`, *When a question that should
hit does not*.

## Where the sentences come from

The set is only as good as the words in it, and the words must come from outside
the document. A question written by the person who wrote the document reuses the
document's vocabulary and matches by construction — a rigged green, and the same
failure `writing-retrievable-knowledge` names for its own question list.

**This list is this skill's, and it is the only one.** The four sources below are in
priority order and the order is part of the definition — it is why traffic outranks
the documents' own claims, which is the whole defence against a rigged green.
Another document may point at this section by name; it may not print a second list,
because two lists that differ at the fourth entry are two skills quietly disagreeing
about where a question is allowed to come from.

In priority order:

| Source | What it gives you | What it cannot give you |
|---|---|---|
| **real traffic and query logs**, where they exist and privacy allows | the user's actual phrasing, including the phrasing nobody would have predicted | anything about a document nobody has had reason to ask for yet |
| **the support inbox** | ticket subject lines, unedited, skewed towards the questions that already failed — which is the skew you want | what already worked, so it will not tell you what to keep |
| **the claims the documents make** | one row per section, mechanically, with a definite end. This is the coverage direction: every section must be some row's `expected_source` | the user's words. These rows need the variants below more than any others |
| **the gaps the tool catalogue leaves** | the questions in the domain that no tool serves — which is precisely the corpus's ground. The ones a tool **does** serve become your best negatives | nothing; run it in both directions and it is the cheapest source here |

Then vary each base question, because one phrasing tests one phrasing:

Every cell in the right-hand column is one of the four layers enumerated in
`SKILL.md`, under *When a question that should hit does not* — that is the only
sense the word carries here:

| Axis | The shape | The layer it can break |
|---|---|---|
| paraphrase | different content words, same intent | **vocabulary** — the failure `writing-retrievable-knowledge` fixes by putting the user's words in the document |
| the terse typed form | no verb, no punctuation, three words | **vocabulary**, stripped bare: whether matching survives on the content words with no sentence around them |
| misspelling, missing accents, wrong plural | one character off | **vocabulary**. Tag it `evasion` — a family is a tag an assertion selects on, never a layer — and `agentic-evals` asserts that family row by row rather than averaging it in |
| abbreviation, and its expansion | both forms, at least once each | **vocabulary**, in the place a corpus most often has only one of the two |
| the other language the product serves | the same question, wholly in it | **the corpus** — whether it covers that language at all. A whole-language failure is a content finding, not a row finding |
| the polite wrapper | "could you please tell me…" around the same content words | **none**, which is exactly why it is a duplicate. See below |

**A variant earns a row when it could fail while its base row passes, and you can
name the layer it would fail at.** If you cannot name the layer, it is a duplicate:
it costs runtime, it moves the denominator every rate divides by, and it dilutes
the family assertions that were the sharpest thing in the file. Politeness wrappers
are the usual duplicate — the content words are identical, so the embedding barely
moves.

**A model may generate variants; it may not generate base questions.** Paraphrasing
a real user turn keeps the user's content words and is exactly what generation is
good at. Generating questions from the document reproduces the document's
vocabulary at scale, and a hundred such rows are a hundred copies of the same
rigged green.

Enough is: every section has one base question, plus a variant on the axis that
section is most exposed to — the abbreviation if it has one, the other language if
the product serves one, the terse form if users type at speed. Rows past that add
runtime and no resolution.
