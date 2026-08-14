---
name: knowledge-and-research
description: Encyclopedic and scholarly lookup — Wikipedia summaries, full-text search and nearby-place articles in Portuguese or English, Wikidata identifiers, and academic papers from Crossref, OpenAlex and PubMed with DOIs, authors and citation counts. Use for "who or what is X", definitions, history and research references.
---

# Knowledge and research

Eleven tools answering two different kinds of question, and the difference
decides which half you reach into.

- **What is this thing?** Wikipedia and Wikidata. Prose for a person, place or
  concept; stable identifiers for the same.
- **Who has published on this?** Crossref, OpenAlex and PubMed. Their results
  are *citations, not facts* — a title in a result set proves the paper exists,
  never that its claim is true. Say "a 2023 paper in Nature reports…", never
  "science says…".

Only Portuguese (`pt`) and English (`en`) are configured. Anything else is
refused, so search in one of the two and translate the answer for the user.

## Choosing a tool

| The user gives you | Use |
|---|---|
| a name or topic, and wants to know what it is | `wikipedia_summary` (with the exact title) |
| a description of something whose article title you do not know | `wikipedia_search` |
| a half-typed or misspelled name | `wikipedia_autocomplete` |
| coordinates, and asks what is notable around there | `wikipedia_nearby` |
| a name that could mean several things, and you need its identifier | `wikidata_search_entity` |
| a Q-number you want confirmed, or a name in the other language | `wikidata_entity_labels` |
| a request for papers, studies or a citation on a topic | `search_papers_crossref` |
| a DOI, or one search result they want read properly | `get_paper_by_doi` |
| "what is the most cited work on…", "the most influential paper" | `search_openalex` |
| a medical, clinical, drug, disease or biology question | `search_pubmed` |
| a PubMed ID from that search | `get_pubmed_summaries` |

## How to combine them

- **Title first, then summary.** `wikipedia_summary` needs the *exact* article
  title. When you are guessing, run `wikipedia_search` (you have a phrase) or
  `wikipedia_autocomplete` (you have a partial spelling), pick the best title,
  then summarize it. Calling `wikipedia_summary` on a guessed title and getting
  nothing back costs the same round trip and tells you less.
- **Never fetch a Wikidata item whole.** `wikidata_search_entity` gives you the
  Q-number and a description; `wikidata_entity_labels` confirms it in both
  languages. That pair is the entire Wikidata surface here — there is no tool
  that reads an item's statements, and there is no query tool.
- **Wikipedia and Wikidata answer different halves.** Wikipedia is the prose;
  Wikidata is the identity. Reach for Wikidata when you need to be sure two
  names refer to the same thing, or when the user wants the term in the other
  language.
- **Nearby needs coordinates.** `wikipedia_nearby` will not take a place name.
  Get coordinates from `find_place` or `search_address_osm` in the
  `geo-and-weather` skill, or from a CEP lookup in `brazil-civic-data`, and pass
  them through.
- **Crossref for the match, OpenAlex for the influence.** `search_papers_crossref`
  finds the paper closest to a title or topic; `search_openalex` ranks by how
  often each work has been cited. "Find me that paper" is the first; "what is
  the foundational work here" is the second.
- **PubMed is a two-step tool.** `search_pubmed` returns bare identifiers, which
  mean nothing to a user. Resolve two or three of them with
  `get_pubmed_summaries` — **one id per call** — and answer from those. Never
  show a raw PubMed ID list as if it were an answer.
- **Deepen one result, do not fan out.** After a Crossref search, use
  `get_paper_by_doi` on the single most relevant DOI. Fetching all three is
  three round trips for an answer that quotes one.

## Reading the results

- **Wikipedia summaries.** `extract` is the article's lead paragraph — a
  compressed summary, not the whole article. It can be stale or contested; for
  anything time-sensitive (an officeholder, a population, a company's status)
  say when in doubt that the encyclopedia may lag. `page` is the article link.
- **Search snippets.** The snippets from `wikipedia_search` carry HTML markup
  around the matched words and are cut mid-sentence. They are for choosing a
  title, not for quoting to the user.
- **Autocomplete.** The result is a positional array: the second element holds
  the suggested titles. There is no description of any of them — pick one and
  summarize it.
- **Nearby.** `dist` is metres from the point you gave, so results read as a
  radius, not a route. A large radius returns landmarks far outside walking
  distance.
- **Wikidata.** `id` is the Q-number, `label` the name and `description` the
  disambiguator — the description is what tells apart the country, the football
  team and the film that share a name.
- **Papers.** `issued.date-parts` is a nested array, `[[2017]]` or
  `[[2017,6,12]]` — read the first number as the year. `container-title` is the
  journal or conference. A Crossref `abstract` arrives with XML tags in it;
  strip them before quoting. `cited_by_count` from OpenAlex is a rough proxy for
  influence, biased towards older papers — a 2024 paper with 30 citations may
  matter more than a 1990 one with 300.
- **PubMed.** `count` is how many papers matched in total, `idlist` the ids you
  can resolve. `pubdate` is a free-text date such as "2024 Mar 15".

## What this skill does not cover

No full article text and no PDFs — only summaries, abstracts and metadata. No
Wikidata statements, no SPARQL, no structured queries over the graph. No arXiv
preprint search, no book or ISBN lookup (that is `books-and-library`), no news,
no patents, and no legal or case-law databases. It cannot tell you whether a
paper's conclusion is correct, only that the paper exists. Say so plainly rather
than approximating from a title.

## When a tool fails

- **Not found** from `wikipedia_summary` almost always means the title was
  wrong, not that the subject is unknown. Run `wikipedia_search` once with the
  user's own phrasing before telling them nothing exists.
- **An empty result list is an answer.** No papers matched, no articles near
  those coordinates, no PubMed hits — report exactly that and suggest a broader
  or differently-worded query. It does not mean the source is down.
- **Invalid arguments** tells you what was wrong: a language other than `pt` or
  `en`, a place name where coordinates belong, a malformed DOI or Q-number, or
  several PubMed ids where one was expected. Fix the call yourself; only ask the
  user when the missing detail is genuinely theirs.
- **Unavailable or refused** on a Wikimedia tool affects Wikipedia and Wikidata
  together — they are the same operator. Fall back to the literature tools if
  the question allows it, say the encyclopedia is unreachable if it does not,
  and do not retry in the same turn.
- **Rate limited** — answer from what you already have and tell the user the
  source is busy. Never loop on it.
