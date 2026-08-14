---
name: books-and-library
description: Books and editions — search by title, subject or author in the international Open Library catalogue, and resolve an ISBN to its publisher, year and page count in either the international or the Brazilian registry. Use for any question about a book, an author's bibliography or an ISBN.
---

# Books and library

Four tools over two catalogues: Open Library, the international one, and the
Brazilian ISBN registry. They overlap far less than you would expect — a book
published in Brazil is frequently absent from Open Library, and an English
technical title is frequently absent from the Brazilian registry. Which catalogue
to ask is a judgement about the book, and it is yours to make.

## Choosing a tool

| The user gives you | Use |
|---|---|
| a title, or a description of a book they half remember | `search_books` |
| a subject, and wants to know what exists about it | `search_books` |
| an author, and wants their books | `list_books_by_author` |
| an ISBN, for a book published in Brazil or written in Portuguese | `get_book_by_isbn_br` |
| an ISBN, for anything else — or one the Brazilian registry did not have | `get_book_by_isbn_intl` |

## How to combine them

- **Search first, identify second.** A title is ambiguous: "Clean Code" has many
  editions with different page counts and publishers. `search_books` gives you
  candidates; only an ISBN pins one edition down. If the user asks how many pages
  a book has and gives no ISBN, say which edition you are describing, or ask.
- **The `key` in a search result is not an ISBN.** It is an Open Library work id
  such as `/works/OL45804W`, and neither ISBN tool accepts it. There is no tool
  here that turns a title into an ISBN — if the user needs edition details, ask
  them for the number under the barcode.
- **Two ISBN tools, one decision.** Try `get_book_by_isbn_br` first for Brazilian
  and Portuguese-language books, `get_book_by_isbn_intl` first for everything
  else. If the first finds nothing, the other one is worth exactly one attempt —
  they are separate catalogues, not mirrors. Calling both for every ISBN wastes a
  round trip.
- **Author questions.** `list_books_by_author` matches on the name as it is
  printed on covers, so "Machado de Assis" works and "the author of Dom Casmurro"
  does not. When you only have a book, `search_books` gets you the author's name
  first.

## Reading the results

- **`numFound` versus what you got back.** The search tools return at most five
  titles and tell you the true total. Say "1,240 matches, here are five" — never
  imply the five are everything, and never claim a book does not exist because it
  was not in a five-item page.
- **`first_publish_year` is the work, not the edition.** A 2019 Portuguese
  translation of a 1899 novel still shows 1899. Do not report it as a publication
  date for the edition in the user's hands.
- **`publishers` and `authors` are lists.** A book with several publishers is one
  edition licensed in several places, not several books.
- **Missing fields are normal.** Older records often lack `number_of_pages` or
  `publish_date`. Say the catalogue does not record it; do not estimate a page
  count.
- **`provider`** on a Brazilian result names which upstream source answered. It
  is provenance, not a quality signal, and is rarely worth telling the user.

## What this skill does not cover

No prices, no availability in a shop, no library holdings or loan status, no full
text and no download links — including public-domain texts, which this build does
not reach. No reviews, ratings or recommendations beyond what a catalogue record
literally contains, and no summaries of plot: if you describe what a book is
about, that is your own knowledge, so say so rather than implying the catalogue
returned it. Academic papers and DOIs belong to the `knowledge-and-research`
skill, not here.

## When a tool fails

- **Not found is an answer.** For an ISBN it usually means the wrong catalogue,
  so try the sibling ISBN tool once. If both come back empty, tell the user the
  number is not registered and ask them to re-read it — an ISBN with one wrong
  digit is a very common cause and looks exactly like a missing book.
- **Invalid arguments** on an ISBN means the value was not 10 or 13 digits. Read
  the number back to the user and ask them to confirm it.
- **Empty search results** mean the wording missed, not that the book is
  imaginary. Try the original-language title, or drop the subtitle.
- **Unavailable or rate limited** — say the catalogue is temporarily unreachable
  and stop. Do not retry in the same turn, and do not fill the gap with a
  remembered publisher or page count; a plausible invented ISBN detail is the
  worst possible answer here.
