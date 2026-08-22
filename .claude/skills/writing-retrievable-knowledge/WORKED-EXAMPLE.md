# A section rewritten so that its chunk answers alone

Open this when rewriting an existing document that does not retrieve, or when
drafting a first section and the rules in [`SKILL.md`](SKILL.md) need a shape.

The domain is invented and neutral: the assistant of a public library, whose
corpus is the library's own rules. The splitter numbers below — **600 characters,
no overlap, cuts at structure, three chunks per answer** — are borrowed from this
repository's own splitter and retrieval settings purely to make the example
concrete, with the overlap zeroed so that one cut is visible end to end.
*What 100 of overlap would have done* below puts the repository's real setting
back and says what it changes. Read your own before applying any of it.

## Before

```markdown
## Renewal policy

Our circulation system allows patrons to extend the borrowing period of most
items in the collection. It is applied automatically at the end of the term,
provided that no other patron has placed a hold on the title and that the
account is in good standing as described above. Where this is not possible,
the item must be returned by the due date printed on the receipt, to avoid the
accrual of overdue charges.
```

430 characters, heading included (`wc -m`) — comfortably inside a 600-character
budget, so nothing here is a length problem. It still fails.

## The chunk the model was given

The section is third in the document. The splitter works **paragraph by
paragraph**, packing them into a segment until the next one will not fit and then
flushing — and a Markdown heading set off by a blank line **is a paragraph of its
own**. The two shorter sections above leave the open segment holding 454
characters; `## Renewal policy` is 17, so it fits and is appended, closing the
segment at 473. The 410-character paragraph under it does not fit, the segment
flushes there, and — **taking the renewal section as the document's last, which
every figure on this page assumes** — the paragraph becomes the whole of the next
chunk:

```
Our circulation system allows patrons to extend the borrowing period of most
items in the collection. It is applied automatically at the end of the term,
provided that no other patron has placed a hold on the title and that the
account is in good standing as described above. Where this is not possible,
the item must be returned by the due date printed on the receipt, to avoid the
accrual of overdue charges.
```

410 characters in which the word *renew* never appears and the rule being stated
is "it". This is not a pathological cut and nothing overflowed: it is the boundary
the structural strategy was choosing on purpose, on a section that stayed
comfortably inside the budget the whole time. **The section's length would never have warned you**, and
neither would reading the document top to bottom.

**And the last-section stipulation only flatters the result.** A renewal section is
almost never the last thing in a document. Put the *What happens if I return a book
late?* section below it — the one this page adds later — and the flush leaves 188
characters of headroom, into which that section's 40-character heading fits and is
packed on behind the paragraph. The chunk is then 452 characters that still never
say *renew*, now carrying the heading of the section they are **not** about. The
packing only ever runs one way: it makes the chunk longer and the subject no
clearer.

## What 100 of overlap would have done

Put this repository's real overlap back — 600 characters with 100 of overlap — and
this particular chunk is rescued, still on the last-section reading. The overlap is
taken as whole sentences from the end of the flushed segment, `## Renewal policy`
is the last of them, so it rides forward and the next chunk opens with its own
heading: 429 characters rather than 410. Exactly 429 also depends on something
the page has not stipulated: the overlap is filled backwards from the end of the
flushed segment, so once the 17-character heading is in it, whatever sentence
precedes the heading rides too if it fits in what is left. Measured, a preceding
sentence of 81 characters produces a 511-character chunk opening on the previous
section's prose. 429 is the case where nothing else fits — which is the ordinary
case, and not a thing anybody arranged.

That is what *insurance against a bad cut* means in [`SKILL.md`](SKILL.md) — and
insurance is all it is. The same page calls overlap "never a licence to write
across" a cut, and says of the heading itself: "never rely on it travelling with
the paragraph under it." Here the insurance happened to pay. It is
conditional on three things the writer does not control: an overlap budget large
enough to hold the heading, a splitter that takes its overlap from the end of the
previous segment at all, and the heading being the last thing in that segment. The
first is the one that decides it — measured, a heading of 118 characters against an
overlap budget of 100 closes the segment at 573 and the overlap comes back empty,
so the next chunk is the same 410 characters, headless. The
rest of this page is what the section has to survive when the rescue does not come
— and the rewrite below survives the cut rather than depending on being spared it.

## Why it does not retrieve, defect by defect

The user typed: *"can I keep this book longer or do I have to bring it back"*.

| Writing defect | What went wrong |
|---|---|
| subject | The section explains renewing a loan. The word *renewal* occurs once, in the heading the cut left behind; inside the chunk the thing being explained is "it". A passage about renewing a loan that never says *loan* or *renew* is a passage about nothing |
| first sentence | The section opens with "Our circulation system allows patrons to…". Preamble is what the section is matched on, and preamble is what it matches |
| vocabulary | The document says *circulation*, *patron*, *hold*, *overdue charges*. The user says *keep it longer*, *bring it back*, *reserved*, *fine*. Not one content word meets |
| cross-reference | "as described above" points at a good-standing rule in another chunk, which will not be retrieved alongside this one. The condition on the answer is unreachable from the answer |
| heading | "Renewal policy" is the filing label of the team that wrote it, not a question anybody types |

Four of those five survive a change of embedding model, and none is visible in the
document while you read it top to bottom.

## After

```markdown
## Can I renew a book and keep it longer?

A loan renews for another three weeks, automatically, on its due date — you do
not have to come back to the desk, and there is no button to press. A loan does
not renew in two cases: somebody else has reserved the same title, or the
account owes more than the fine limit. When a loan does not renew, the book is
due on the date printed on the receipt and a late fee — the fines on your
account — starts the day after.
```

461 characters (`wc -m`): 31 more than the original, 7% longer, and still inside
the 600-character budget. **Rich did not mean bigger.** It meant that the words a
user types are in the text, the subject is named where the cut can reach it, and
the exception is answered in the same chunk as the rule.

**The cut does not change; what arrives does.** Run the rewritten document through
the same splitter at the same settings and the boundary lands in exactly the same
place — the question-heading packs onto the segment above, the paragraph starts the
next chunk, headless as before. That chunk is 417 characters and it opens *"A loan
renews for another three weeks, automatically, on its due date."* 410 characters
that never named their subject became 417 that answer the question, at the same
boundary, from a splitter nobody reconfigured. Restore the late-return section and
both figures gain the same 42 characters of packed-on heading — 452 before, 459
after — which changes neither the boundary nor the point: the rewrite survives the
cut, and the original never did.

What each change bought:

- **"A loan"** replaces "it", in the first sentence and again in the third. Cut this
  section anywhere — including at the heading boundary that orphaned the original —
  and the surviving half still says what it is about.
- **The answer is the first clause.** "Renews for another three weeks,
  automatically, on its due date" is what carries the section in the embedding.
- **Both vocabularies are present**: *renew* and *keep it longer*; *reserved* rather
  than *hold*; *late fee* and *fines*, because half the users have only ever seen one
  of those two words.
- **The condition is inlined.** "Owes more than the fine limit" says what "good
  standing" meant, in this chunk, rather than pointing at a chunk that will not come.
- **The heading is the question**, so it matches whether or not the splitter carries
  headings into chunks — and if it does carry them, it matches twice.

## The trap on the next section down

The document also needs *"What happens if I return a book late?"*. The pull is to
restate the renewal rule there for completeness, and that is the failure described
under *Repeat the subject, not the explanation* in [`SKILL.md`](SKILL.md): two
sections saying the same thing both match the renewal question, both take a slot of
the three, and the answer is then assembled from two facts wearing three chunks.

Give the late-return section its own subject and its own facts — the daily rate, the
cap, the point at which borrowing stops — and let it restate exactly one sentence of
the renewal rule, the one it cannot be understood without: *"A loan that somebody
else has reserved does not renew, so it becomes late on its due date."*

## This rewrite is a hypothesis

Nothing above proves the new section retrieves for the question that failed. Reading
a document is not evidence about an embedding, and neither is a chunk boundary you
have watched land where you wanted it. Ask the user to run `corpus-retrieval-tests`,
which they invoke by name — a model cannot load it, so nothing said here reaches it
on its own. That skill turns "can I keep this book longer" into a row carrying the
question, the intended chunk — `expected_source` in the query set — and the
negatives, and it owns the diagnosis when the row still fails — which of the four
layers missed: corpus, chunk boundary, vocabulary, threshold-or-router, in that
order.
